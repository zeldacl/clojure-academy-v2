(ns cn.li.combat.runtime
  "Authoritative Clojure combat execution runtime.

   The runtime only produces neutral plans. AC owns persistence and host
   adapters execute the returned world effects and VFX signals."
  (:require [cn.li.mcmod.runtime.combat-contract :as contract]
            [cn.li.combat.compiler :as compiler]
            [cn.li.combat.damage :as damage]))

(defn- empty-result [] (contract/result {}))
(defn- reject [reason data]
  (contract/result {:status :rejected :feedback [(merge {:reason reason} data)]}))

(defn create-engine
  [{:keys [catalog initial-owner-state query-port now-tick ability-resolver
           damage-pipeline max-seen-intents]
    :or {initial-owner-state (fn [_] {}) now-tick (fn [] 0)}}]
  (when-not (map? catalog) (throw (ex-info "combat engine requires compiled catalog" {})))
  {:catalog catalog
   :sessions (atom {})
   :owner-state (or initial-owner-state (fn [_] {}))
   :ability-resolver ability-resolver
   :damage-pipeline (damage/compile-pipeline damage-pipeline)
   :query-port (or query-port {})
   :now-tick now-tick
   :seen-intents (atom {})
   :max-seen-intents (long (or max-seen-intents 4096))
   :deadline-queue (atom (sorted-map))
   :last-tick (atom nil)})

(defn- ability [engine id]
  (get-in (:catalog engine) [:abilities id]))

(defn- append-output [result k values]
  (if (seq values) (update result k into values) result))

(defn- skill-exp
  [context ability-id]
  (double (or (get-in context [:state :ability-data :skill-exps ability-id])
              (get-in context [:state :skill-exp ability-id])
              0.0)))

(defn- resolve-value
  "Resolve bounded, data-only runtime expressions used by skill content.
   Expressions are immutable data and never invoke host/platform code."
  [value context]
  (if-not (map? value)
    value
    (case (:op value)
      :scale (let [exp (skill-exp context (or (:ability-id value)
                                               (:ability-id context)))
                   lo (double (:min value))
                   hi (double (:max value))]
               (+ lo (* (- hi lo) (max 0.0 (min 1.0 exp)))))
      :add (reduce + 0.0 (map #(double (resolve-value % context)) (:values value)))
      :multiply (reduce * 1.0 (map #(double (resolve-value % context)) (:values value)))
      :session (double (or (get-in (:session-state context) (:path value)) 0.0))
      :clamp (let [v (double (resolve-value (:value value) context))
                   lo (double (:min value))
                   hi (double (:max value))]
               (max lo (min hi v)))
      value)))

(defn- resolve-cost
  [cost context]
  (into {} (map (fn [[resource amount]]
                  [resource (double (resolve-value amount context))]) cost)))

(declare resolve-data)

(defn- resolve-data
  "Resolve data-only expressions recursively without invoking host code."
  [value context]
  (cond
    (and (map? value) (:op value)) (resolve-value value context)
    (map? value) (reduce-kv (fn [m key nested]
                              (assoc m key (resolve-data nested context)))
                            {} value)
    (vector? value) (mapv #(resolve-data % context) value)
    (seq? value) (mapv #(resolve-data % context) value)
    :else value))

(defn- activation-cost
  "Resolve only the cost due at the current lifecycle phase.

   Session recipes may declare :cost-phase :pulse/release; their start still
   validates affordability against the same amount, but does not deduct it
   until that phase executes."
  [ability context]
  (if (and (:cost-phase ability)
           (not= (:cost-phase ability) (:phase context)))
    {}
    (resolve-cost (:cost ability) context)))

(defn- ref-path
  [context ref path]
  (get-in (get-in context [:refs ref]) path))

(defn- resolve-session-patch
  [entries context]
  (mapv (fn [[path value]]
          [path (if (and (map? value) (= :increment (:op value)))
                  {:op :increment
                   :amount (double (resolve-value (or (:amount value) 1.0)
                                                  context))}
                  (resolve-value value context))]) entries))

(defn- apply-session-patches
  [state patches]
  (reduce (fn [acc [path value]]
            (if (and (map? value) (= :increment (:op value)))
              (update-in acc path (fnil + 0.0) (double (:amount value)))
              (assoc-in acc path value)))
          (or state {}) patches))

(defn- combine-results [left right]
  (let [result (into {:status (cond
                                (= :rejected (:status left)) :rejected
                                (= :rejected (:status right)) :rejected
                                (= :halt (:status right)) :halt
                                :else (:status left))}
                     (map (fn [key]
                            [key (vec (concat (or (get left key) [])
                                             (or (get right key) [])))])
                          [:state-patch :session-patch :world-effects :vfx-signals :events :feedback]))]
    (assoc result :context (or (:context right) (:context left)))))

(defn- run-node [engine node context]
  (case (:op node)
    :sequence (reduce (fn [result child]
                       (if (#{:halt :rejected} (:status result))
                         result
                         (combine-results result
                                          (run-node engine child
                                                    (or (:context result) context)))))
                     {:status :continue :context context} (:steps node))
    :repeat (reduce (fn [result _]
                      (if (#{:halt :rejected} (:status result))
                        result
                        (combine-results result
                                         (run-node engine
                                                   {:op :sequence :steps (:steps node)}
                                                   context))))
                    {:status :continue :context context}
                    (range (long (:count node))))
    :phase (if-let [selected (get node (:phase context))]
             (run-node engine selected context)
             {:status :continue :context context})
    :branch (if-let [selected (if (or (get-in context [:flags (:predicate node)])
                                      (and (:predicate-ref node)
                                           (some? (get-in context [:refs (:predicate-ref node)]))))
                                (:then node) (:else node))]
              (run-node engine selected context)
              {:status :continue})
    :require (if (or (get-in context [:flags (:predicate node)])
                     (some? (get-in context [:refs (:predicate node)])))
               {:status :continue}
               {:status :rejected :feedback [{:reason :required-condition-failed}]})
    :require-session (let [value (get-in (:session-state context) (:path node))
                           value (double (or value 0.0))
                           min-value (when (contains? node :min)
                                       (double (:min node)))
                           max-value (when (contains? node :max)
                                       (double (:max node)))]
                       (if (and (or (nil? min-value) (>= value min-value))
                                (or (nil? max-value) (<= value max-value)))
                         {:status :continue}
                         {:status :rejected
                          :feedback [{:reason :session-window-failed
                                      :path (:path node)
                                      :value value
                                      :min min-value
                                      :max max-value}]}))
    :query (let [query-fn (get (:queries context) (:query-type node))
                 query-node (reduce (fn [resolved key]
                                     (if (contains? #{:distance :range :aoe-radius} key)
                                       (assoc resolved key (resolve-value (get resolved key)
                                                                          context))
                                       resolved))
                                   node
                                   [:distance :range :aoe-radius])]
             (if-not (ifn? query-fn)
               {:status :rejected :feedback [{:reason :missing-query-port
                                              :query-type (:query-type node)}]}
               (let [value (query-fn context query-node)
                     context (cond-> (assoc-in context [:refs (:result-ref node :hit)] value)
                               (map? (:result-paths node))
                               (as-> c (reduce-kv (fn [acc ref path]
                                                    (assoc-in acc [:refs ref]
                                                              (get-in value path)))
                                                  c (:result-paths node)))
                               (map? (:result-flags node))
                               (as-> c (reduce-kv (fn [acc flag path]
                                                    (assoc-in acc [:flags flag]
                                                              (some? (get-in value path))))
                                                  c (:result-flags node))))]
                 (let [context (cond-> context
                                 (and (map? value) (:world-id value))
                                 (assoc :world-id (:world-id value)))]
                 {:status :continue
                :context context
                :events [{:type :query
                          :query-type (:query-type node)
                           :result value}]}))))
    :damage (let [target (or (:target node)
                             (get-in context [:refs (:target-ref node)])
                             (get-in context [:refs :target]))
                  target (if (map? target)
                           (or (:entity-id target) (:target-id target) (:uuid target) target)
                           target)
                  request (damage/apply-pipeline
                           (:damage-pipeline engine)
                           {:source (:owner context)
                            :target target
                            :base (double (resolve-value (:amount node) context))
                            :type (:type node)
                            :components {:direct (double (resolve-value (:amount node) context))}
                            :tags #{:skill}
                            :metadata {:ability-id (:ability-id context)
                                       :world-id (:world-id context)}}
                           context)]
              (if (:cancelled? request)
                {:status :continue}
                {:status :continue :world-effects [(contract/world-effect
                                                    {:type :damage
                                                     :request request})]}))
    :vfx {:status :continue
          ;; A combat output is authoritative creation-or-update.  Using
          ;; :spawn keeps the first confirmed signal from being dropped when
          ;; the client has no local instance yet; vfx-core makes repeated
          ;; stable-key spawns idempotent and sequence-checked.
          :vfx-signals [(contract/signal {:op :spawn
                                           :effect-id (:effect-id node)
                                           :instance-key (or (:instance-key node)
                                                             [:combat (:session-id context) (:effect-id node)])
                                           :owner (:owner context)
                                           :world-id (:world-id context)
                                           :event-seq (long (or (:event-seq context) 0))
                                           :seed (long (or (:seed context) 0))
                                           :event (:event node)
                                           :params (if-let [params-ref (:params-ref node)]
                                                     (get-in context [:refs params-ref])
                                                     (:params node))})]}
    :world-effect (let [effect (resolve-data (dissoc node :op :effect-type) context)
                        target (when-let [target-ref (:target-ref node)]
                                 (get-in context [:refs target-ref]))
                        target (if (and (:target-ref node) (:target-path node))
                                 (get-in (get-in context [:refs (:target-ref node)])
                                         (:target-path node))
                                 target)
                        targets (when-let [targets-ref (:targets-ref node)]
                                  (get-in context [:refs targets-ref]))
                        targets (if (and (:targets-ref node) (:targets-path node))
                                  (get-in (get-in context [:refs (:targets-ref node)])
                                          (:targets-path node))
                                  targets)
                        origin (when-let [origin-ref (:origin-ref node)]
                                 (get-in context [:refs origin-ref]))
                        origin (if (and (:origin-ref node) (:origin-path node))
                                 (get-in (get-in context [:refs (:origin-ref node)])
                                         (:origin-path node))
                                 origin)
                        scan (when-let [scan-ref (:scan-ref node)]
                               (get-in context [:refs scan-ref]))
                        query-result (when-let [query-ref (:query-ref node)]
                                       (get-in context [:refs query-ref]))
                        projectile-spec (when (map? (:projectile-spec node))
                                          (reduce-kv
                                           (fn [spec key value]
                                             (if (= key :target-ref)
                                               (assoc spec :target
                                                      (get-in context [:refs value]))
                                               (assoc spec key (resolve-value value context))))
                                           {}
                                           (:projectile-spec node)))
                        effect (reduce (fn [resolved key]
                                         (if (contains? resolved key)
                                           (update resolved key resolve-value context)
                                           resolved))
                                       effect
                                       [:amount :plain-damage :scattered-damage
                                        :range :cone-angle-degrees])
                        effect (cond-> effect
                                 (some? target) (assoc :target target)
                                 (some? targets) (assoc :targets targets)
                                 (some? origin) (assoc :origin origin)
                                 (some? scan) (assoc :scan scan)
                                 (some? query-result) (assoc :query-result query-result)
                                 (:ability-id context) (assoc :ability-id (:ability-id context))
                                 (:session-id context) (assoc :session-id (:session-id context))
                                 projectile-spec (assoc :projectile-spec projectile-spec)
                                 (:world-id context) (assoc :world-id (:world-id context))
                                 true (dissoc :target-ref :target-path
                                               :targets-ref :targets-path
                                               :origin-ref :origin-path :scan-ref :query-ref
                                               :projectile-spec))]
                    {:status :continue
                     :world-effects [(contract/world-effect
                                      (assoc effect :type (:effect-type node)))]})
    :domain-event {:status :continue
                   :events [(contract/domain-event
                             (assoc (dissoc node :op :event-type)
                                    :type (:event-type node)))]}
    :patch {:status :continue
            :state-patch (mapv (fn [[kind key value]]
                                 [kind key (resolve-value value context)])
                               (:entries node))}
    :session-patch {:status :continue
                    :session-patch (resolve-session-patch (:entries node) context)}
    :node (let [descriptor (get-in (:catalog engine) [:nodes (:node-id node)])]
            (if-let [run (:run descriptor)]
              (run context node)
              {:status :rejected :feedback [{:reason :unknown-node :node-id (:node-id node)}]}))
    {:status :rejected :feedback [{:reason :unknown-op :op (:op node)}]}))

(defn- execute [engine ability context]
  (let [result (run-node engine (:program ability) context)
        cost (if (:skip-cost? context)
               {}
               (activation-cost ability context))
        result (if (and (seq cost) (not= :rejected (:status result)))
                 (update result :state-patch into (mapv (fn [[resource amount]]
                                                          [:resource resource (- (double amount))]) cost))
                 result)]
    (if (= :rejected (:status result))
      (contract/result (assoc result
                             :ability-id (:ability-id ability)
                             :owner (:owner context)
                             :program-hash (:program-hash ability)
                             :content-hash (get-in engine [:catalog :content-hash])))
      (contract/result (assoc (dissoc result :status)
                              :ability-id (:ability-id ability)
                              :owner (:owner context)
                              :program-hash (:program-hash ability)
                              :content-hash (get-in engine [:catalog :content-hash]))))))

(defn- mark-intent! [engine owner intent-id]
  (swap! (:seen-intents engine)
         update owner
         (fn [history]
           (let [history (vec (or history []))
                 history (if (some #(= intent-id %) history)
                           history
                           (conj history intent-id))
                 limit (:max-seen-intents engine)]
             (if (> (count history) limit)
               (subvec history (- (count history) limit))
               history)))))

(defn- seen-intent? [engine owner intent-id]
  (some #(= intent-id %)
        (get @(:seen-intents engine) owner [])))

(defn- resolve-ability-id [engine owner intent]
  (if-let [resolver (:ability-resolver engine)]
    (resolver owner intent)
    (:ability-id intent)))

(defn- session-id-for-intent
  "Resolve release/abort to the owner's unique active session when the
   network envelope intentionally omits a server-generated session id."
  [engine owner intent]
  (or (:session-id intent)
      (let [ability-id (resolve-ability-id engine owner intent)
            matches (for [[session-id session] @(:sessions engine)
                          :when (and (= owner (:owner session))
                                     (or (nil? ability-id)
                                         (= ability-id (:ability-id session))))]
                      session-id)]
        (when (= 1 (count matches))
          (first matches)))))

(defn- cooldown-ready? [state ability-id tick]
  (or (not (contains? state :cooldowns))
      (not (pos? (long (or (get-in state [:cooldowns ability-id]) 0))))))

(defn- resources-available? [state cost]
  (or (not (contains? state :resources))
      (every? (fn [[resource amount]]
                (>= (double (or (get-in state [:resources resource]) 0.0))
                    (double amount))) cost)))

(defn- start! [engine owner intent]
  (let [ability-id (resolve-ability-id engine owner intent)
        ability (ability engine ability-id)]
    (cond
      (nil? ability) (reject :unknown-ability {:ability-id ability-id})
      (= :passive (:activation ability))
      (reject :passive-ability-cannot-start {:ability-id ability-id})
      :else
      (let [state ((:owner-state engine) owner)
            tick (long ((:now-tick engine)))
            cost (resolve-cost (:cost ability)
                               {:ability-id ability-id :state state
                                :phase :start})]
        (cond
          (not (resources-available? state cost))
          (reject :insufficient-resource {:ability-id ability-id})
          (not (cooldown-ready? state ability-id tick))
          (reject :cooldown-active {:ability-id ability-id})
          :else
          (let [session-id (or (:session-id intent) [owner (:intent-id intent)])
                context {:owner owner :session-id session-id :input intent
                         :ability-id ability-id :state state
                         :session-state {}
                         :tick tick :phase :start
                         :queries (:query-port engine)
                         :flags (:flags intent) :refs (:refs intent)
                         :event-seq 0}
                result (execute engine ability context)
                result (if (and (not= :rejected (:status result))
                                (seq (:cooldown ability))
                                (not= :release (:cooldown-phase ability)))
                         (update result :state-patch conj
                                 [:cooldown ability-id
                                  (+ tick (long (resolve-value
                                                 (:ticks (:cooldown ability))
                                                 context)))])
                         result)]
            (if (= :rejected (:status result))
              result
              (if (#{:session :toggle} (:activation ability))
                (let [deadline (+ tick (long (or (:period-ticks ability) 1)))]
                  (swap! (:sessions engine) assoc session-id
                          {:session-id session-id :owner owner :ability-id ability-id
                          :phase :active :next-deadline deadline
                          :started-tick tick
                          :intent-id (:intent-id intent)
                          :state (apply-session-patches {}
                                                        (:session-patch result))})
                  (swap! (:deadline-queue engine) update deadline
                         (fnil conj #{}) session-id)
                  (update result :session-ops conj
                          {:op :start :session-id session-id :owner owner
                           :ability-id ability-id :next-deadline deadline}))
                result))))))))

(defn- release! [engine owner intent]
  (let [session-id (session-id-for-intent engine owner intent)]
    (if-let [session (get @(:sessions engine) session-id)]
    (let [ability (ability engine (:ability-id session))
          context {:owner owner :session-id session-id
                   :ability-id (:ability-id session)
                   :session-state (:state session)
                   :tick (long ((:now-tick engine)))
                   :input intent :phase :release
                   :state ((:owner-state engine) owner)
                   :queries (:query-port engine)
                   :flags (:flags intent) :refs (:refs intent)
                   :event-seq (long (or (:event-seq intent) 1))}
          result (execute engine ability context)
          result (if (and (not= :rejected (:status result))
                          (= :release (:cooldown-phase ability))
                          (seq (:cooldown ability)))
                   (update result :state-patch conj
                           [:cooldown (:ability-id session)
                            (+ (:tick context)
                               (long (resolve-value (:ticks (:cooldown ability))
                                                    context)))])
                   result)]
      (swap! (:sessions engine) dissoc (:session-id session))
      (update result :session-ops conj
              {:op :release :session-id session-id :owner owner}))
    (reject :unknown-session {:session-id session-id}))))

(defn- abort! [engine owner intent]
  (let [session-id (session-id-for-intent engine owner intent)]
    (if (contains? @(:sessions engine) session-id)
    (let [session (get @(:sessions engine) session-id)
          ability (ability engine (:ability-id session))
          result (execute engine ability {:owner owner
                                          :session-id session-id
                                          :ability-id (:ability-id session)
                                          :session-state (:state session)
                                          :tick (long ((:now-tick engine)))
                                          :phase :abort
                                          :skip-cost? true
                                          :input intent
                                          :state ((:owner-state engine) owner)
                                          :queries (:query-port engine)
                                          :flags (:flags intent)
                                          :refs (:refs intent)
                                          :event-seq (long (or (:event-seq intent) 1))})]
      (swap! (:sessions engine) dissoc session-id)
      (update result :session-ops conj
              {:op :abort :session-id session-id :owner owner}))
    (reject :unknown-session {:session-id session-id}))))

(defn dispatch-intent! [engine owner raw-intent]
  (let [intent (contract/intent (assoc raw-intent :owner owner))]
    (if (seen-intent? engine owner (:intent-id intent))
      (reject :duplicate-intent {:intent-id (:intent-id intent)})
      (do
        (mark-intent! engine owner (:intent-id intent))
        (case (:op intent)
          :start (start! engine owner intent)
          :release (release! engine owner intent)
          :abort (abort! engine owner intent))))))

(defn tick! [engine tick]
  (when-not (= tick @(:last-tick engine))
    (reset! (:last-tick engine) tick)
    (let [due (get @(:deadline-queue engine) tick #{})]
      (swap! (:deadline-queue engine) dissoc tick)
      (mapv (fn [session-id]
              (if-let [session (get @(:sessions engine) session-id)]
                (let [ability (ability engine (:ability-id session))
                      expired? (and (:max-session-ticks ability)
                                    (> (- (long tick)
                                          (long (or (:started-tick session) tick)))
                                       (long (:max-session-ticks ability))))]
                  (if expired?
                    (let [result (execute engine ability {:owner (:owner session)
                                                          :session-id session-id
                                                          :ability-id (:ability-id session)
                                                          :session-state (:state session)
                                                          :tick tick :phase :abort :skip-cost? true
                                                          :state ((:owner-state engine) (:owner session))
                                                          :queries (:query-port engine)
                                                          :event-seq tick})]
                      (swap! (:sessions engine) dissoc session-id)
                      (update result :session-ops conj
                              {:op :abort :reason :max-session-ticks
                               :session-id session-id :owner (:owner session)}))
                    (let [result (execute engine ability {:owner (:owner session)
                                                          :session-id session-id
                                                          :ability-id (:ability-id session)
                                                          :session-state (:state session)
                                                          :tick tick :phase :pulse
                                                          :state ((:owner-state engine) (:owner session))
                                                          :queries (:query-port engine)
                                                          :event-seq tick})
                          next-tick (+ tick (long (or (:period-ticks ability) 1)))]
                      (swap! (:sessions engine)
                             (fn [sessions]
                               (-> sessions
                                   (assoc-in [session-id :next-deadline] next-tick)
                                   (assoc-in [session-id :state]
                                             (apply-session-patches
                                              (:state session)
                                              (:session-patch result))))))
                      (swap! (:deadline-queue engine) update next-tick
                             (fnil conj #{}) session-id)
                       result)))
                (reject :unknown-session {:session-id session-id})))
            due))))

(defn dispatch-domain-event! [engine event]
  (reduce (fn [results [_ ability]]
            (if (= :passive (:activation ability))
              (conj results (execute engine ability {:owner (:owner event)
                                                     :session-id (:event-id event)
                                                     :phase :passive
                                                     :tick (long ((:now-tick engine)))
                                                     :event event
                                                     :flags (:flags event)
                                                     :refs (:refs event)
                                                     :state ((:owner-state engine) (:owner event))
                                                     :queries (:query-port engine)
                                                     :event-seq (long (or (:event-seq event) 0))}))
              results)) [] (get-in engine [:catalog :abilities])))

(defn abort-owner! [engine owner]
  (let [ids (for [[id session] @(:sessions engine) :when (= owner (:owner session))] id)]
    (swap! (:sessions engine) #(apply dissoc % ids))
    (swap! (:seen-intents engine) dissoc owner)
    (vec ids)))

(defn snapshot-owner [engine owner]
  {:owner owner
   :sessions (vec (for [[_ session] @(:sessions engine)
                        :when (= owner (:owner session))]
                    session))
   :content-hash (get-in engine [:catalog :content-hash])})

(ns cn.li.combat.reactions
  "Generic declarative damage reaction evaluator.

   Reactions are ordinary EDN data.  State/session access is injected by the
   caller; this namespace has no AC or skill knowledge."
  (:require [cn.li.combat.vm :as vm]))

(defn- scale-components [value factor]
  (cond
    (number? value) (* (double value) (double factor))
    (map? value) (reduce-kv (fn [m k v]
                              (assoc m k (scale-components v factor)))
                            (empty value) value)
    (vector? value) (mapv #(scale-components % factor) value)
    (seq? value) (mapv #(scale-components % factor) value)
    :else value))

(defn- value [value context]
  (cond
    (and (map? value) (:expr value))
    (vm/evaluate-expression (:expr value)
                            (mapv #(value % context) (:args value))
                            (long (or (:activation-seed context) 0)))
    (and (map? value) (:ref value))
    (let [[scope key & path] (:ref value)
          root (case scope
                 :context (:context context) :request (:request context)
                 :param (:params context) :session (:session-state context)
                 :state (:state context) {})]
      (get-in root (into [key] path)))
    (and (map? value) (contains? value :tunable))
    (let [resolved (get (:tunables context) (:tunable value))]
      (if (contains? value :path)
        (get-in resolved (:path value))
        resolved))
    (map? value) (reduce-kv (fn [m k v] (assoc m k (value v context)))
                            (empty value) value)
    (vector? value) (mapv #(value % context) value)
    :else value))

(defn- critical-level
  "Select the first successful critical level using the activation/request
   seed.  A level is ordinary EDN data; no skill or damage type is embedded in
   this evaluator."
  [levels context]
  (let [seed (long (or (:activation-seed context) 0))]
    (some (fn [[index level]]
            (let [probability (double (or (value (:probability level) context)
                                          0.0))]
              (when (and (Double/isFinite probability)
                         (pos? probability) (<= probability 1.0)
                         (boolean (vm/evaluate-expression
                                  :random/chance [probability]
                                  (unchecked-add seed (long index)))))
                level)))
          (map-indexed vector (or levels [])))))

(defn- request-position [request]
  (or (get-in request [:metadata :target-position])
      (get-in request [:metadata :position])
      [0.0 0.0 0.0]))

(defn- critical-exp-gain
  [program context level-index]
  (let [amount (double (or (value (:exp-per-level program) context) 0.0))]
    (if (= :fixed (:exp-mode program))
      amount
      (* amount (inc (long level-index))))))

(defn- critical-result
  "Apply one deterministic critical roll for all independent critical
   contributions.  Multiple passive abilities may add probability to the same
   level; they must not roll independently or multiply damage twice."
  [current contributions source target]
  (let [base (double (:base current))
        reflected? (boolean (some #(get-in % [:context :context :reflected?])
                                  contributions))
        allowed? (fn [{:keys [program context]}]
                   (and (boolean (get-in context [:context :source-learned?]))
                        (contains? (set (or (:damage-types program) []))
                                   (:type current))))
        eligible? (and source (not= (str source) (str target))
                       (not= (str source) "environment")
                       (pos? base) (Double/isFinite base)
                       (not reflected?))
        active (if eligible? (filter allowed? contributions) [])
        levels (reduce (fn [result contribution]
                         (let [program (:program contribution)
                               context (:context contribution)]
                           (reduce (fn [result level]
                                     (update result (long (or (:level level) 0))
                                             (fnil conj [])
                                             (assoc contribution :level level)))
                                   result (:levels program))))
                       (sorted-map) active)
        selected-level (some (fn [[level entries]]
                               (let [probability (min 1.0
                                                       (reduce + 0.0
                                                               (map #(double (or (value (get-in % [:level :probability])
                                                                                         (:context %))
                                                                                 0.0))
                                                                    entries)))]
                                 (when (and (Double/isFinite probability)
                                            (pos? probability)
                                            (boolean (vm/evaluate-expression
                                                      :random/chance
                                                      [probability]
                                                      (unchecked-add
                                                       (long (or (get-in (first entries) [:context :activation-seed]) 0))
                                                       (long level)))))
                                   [level entries])))
                             levels)]
    (if-not selected-level
      current
      (let [[level entries] selected-level
            multiplier (double
                       (or (some (fn [{:keys [level context]}]
                                   (when (contains? level :multiplier)
                                     (value (:multiplier level) context)))
                                 entries)
                           1.0))
            scaled (* base multiplier)
            ratio (if (pos? base) (/ scaled base) 0.0)
            presentation (some #(when (:vfx (:program %)) %) entries)
            vfx (:vfx (:program presentation))
            vfx-signal (when vfx
                         (let [context (:context presentation)]
                           {:op :spawn
                            :effect-id (:effect-id vfx)
                            :instance-key (or (:instance-key vfx)
                                              [(:ability-id presentation) :critical])
                            :owner source
                            :world-id (get-in context [:context :world-id])
                            :audience (:audience vfx)
                            :event-seq 0
                            :seed (:activation-seed context)
                            :event :spawn
                            :params (value (:payload vfx) context)}))
            feedback (some #(when (:feedback (:program %)) %) entries)
            feedback-context (when feedback
                              (assoc-in (:context feedback)
                                        [:context :critical-multiplier]
                                        multiplier))
            events (->> entries
                        (mapcat (fn [{:keys [program]}]
                                  (concat (or (:events program) [])
                                          (or (get-in program [:events-by-level level])
                                              (get-in program
                                                      [:events-by-level
                                                       (keyword (str "level-" level))])
                                              []))))
                        distinct
                        vec)
            events (cond-> events
                     feedback
                     (conj {:type :player/feedback
                            :payload (value (:feedback (:program feedback))
                                            feedback-context)}))
            result (-> current
                       (assoc :base scaled)
                       (assoc :components (scale-components (:components current) ratio))
                       (assoc-in [:metadata :critical]
                                 {:ability-id (:ability-id (first entries))
                                  :level (long level)
                                  :multiplier multiplier
                                  :damage-before base
                                  :damage-after scaled})
                       (update :source-state-patch (fnil into [])
                               (keep (fn [{:keys [ability-id program context]}]
                                       (let [gain (critical-exp-gain program context level)]
                                         (when (and (Double/isFinite gain) (pos? gain))
                                           [:ability-exp ability-id gain])))
                                     entries))
                       (update :events (fnil into []) events))]
        (if vfx-signal
          (update result :vfx-signals (fnil conj []) vfx-signal)
          result)))))

(defn apply!
  [request {:keys [reactions session-fn state-fn precheck? tunables-fn
                  domain-state]}]
  (let [reactions (->> reactions
                       (mapcat (fn [entry]
                                 (if (seq (:reactions entry))
                                   (map #(assoc %
                                                :ability-id (:id entry)
                                                :activation (:activation entry))
                                        (:reactions entry))
                                   [entry])))
                       (filter #(= :combat/damage (:on %)))
                       (sort-by (juxt #(long (or (:priority %) 0))
                                      #(str (:ability-id %)))))
        critical-reactions (filter #(= :damage/critical
                                        (get-in % [:program :component]))
                                   reactions)
        critical-anchor-id (:ability-id (first critical-reactions))]
    (reduce
     (fn [current {:keys [ability-id activation program mark-type]
                   when-condition :when}]
       (let [target (str (:target current))
             source (:source current)
             source-id (when source (str source))
             session (session-fn target)
             state (state-fn target)
             source-session (when (and source-id
                                       (not= source-id "environment")
                                       (not= source-id "nil"))
                              (session-fn source-id))
             source-state (when (and source-id
                                     (not= source-id "environment")
                                     (not= source-id "nil"))
                            (state-fn source-id))
             mark (when mark-type
                    (get-in domain-state [:entity-marks target mark-type]))
             context {:request current :state state
                      :source-state source-state
                      :source-session-state (:state source-session)
                      :domain-state domain-state
                      :session-state (:state session)
                      :params (:parameter-snapshot session)
                      :tunables (tunables-fn ability-id
                                              (if (= :damage/critical (:component program))
                                                source-session session)
                                              (if (= :damage/critical (:component program))
                                                source-state state))
                      :activation-seed (long (or (:activation-seed current)
                                                 (get-in current [:metadata :activation-seed])
                                                 (:activation-seed session)
                                                 (:activation-seed source-session)
                                                 0))
                      :context {:enabled? (boolean session)
                                :ability-id (:ability-id session)
                                :source source
                                :source-id source-id
                                :source-enabled? (boolean source-session)
                                :source-learned? (contains?
                                                  (get-in source-state
                                                          [:ability-data :learned-skills] #{})
                                                  ability-id)
                                :source-skill-exp (double
                                                   (or (get-in source-state
                                                               [:ability-data :skill-exps ability-id])
                                                       0.0))
                                :damage-type (:type current)
                                :reflected? (contains? (:tags current) :reflected)
                                :front? (boolean (get-in current [:metadata :attacker-front?]))
                                :resource (double (or (get-in state [:resources :cp]) 0.0))
                                :skill-exp (double (or (get-in state [:ability-data :skill-exps ability-id]) 0.0))
                                :depth (long (or (get-in current [:metadata :reflection-depth]) 0))
                                :damage (double (:base current))
                                :max-cp (double (or (get-in state [:resources :max-cp]) 0.0))
                                :mark mark
                                :mark? (boolean mark)
                                :mark-rate (double (or (:rate mark) 1.0))
                                :owner target
                                :target-position (request-position current)
                                :world-id (or (get-in current [:metadata :world-id])
                                              (:world-id state))}}
             condition? (if (= :damage/critical (:component program))
                          true
                           (or (nil? when-condition)
                               (boolean (value when-condition context))))
             component (:component program)]
         (if-not (and (or session (= :passive activation)) condition?)
           current
           (do
             (case component
               :damage/critical
               (if (= ability-id critical-anchor-id)
                 (let [contributions
                       (keep (fn [{entry-id :ability-id
                                   entry-when :when
                                   entry-program :program}]
                               (let [entry-context
                                     (assoc context
                                            :tunables
                                            (tunables-fn entry-id
                                                         source-session
                                                         source-state)
                                            :context
                                            (assoc (:context context)
                                                   :ability-id entry-id
                                                   :source-learned?
                                                   (contains?
                                                    (get-in source-state
                                                            [:ability-data :learned-skills] #{})
                                                    entry-id)
                                                   :source-skill-exp
                                                   (double
                                                    (or (get-in source-state
                                                                [:ability-data :skill-exps entry-id])
                                                        0.0))))]
                                 (when (or (nil? entry-when)
                                           (boolean (value entry-when entry-context)))
                                   {:ability-id entry-id
                                    :program entry-program
                                    :context entry-context})))
                             critical-reactions)]
                   (critical-result current contributions source target))
                 current)
           :damage/multiply
             (let [base (double (:base current))
                   multiplier (double (or (value (:multiplier program) context) 1.0))]
               (if (and (pos? base) (Double/isFinite multiplier)
                        (>= multiplier 0.0) (<= multiplier 16.0))
                 (let [scaled (* base multiplier)
                       ratio (if (pos? base) (/ scaled base) 0.0)]
                   (-> current
                       (assoc :base scaled)
                       (assoc :components (scale-components (:components current) ratio))
                       (assoc-in [:metadata :damage-multiplier]
                                 {:ability-id ability-id
                                  :multiplier multiplier
                                  :mark mark})))
                 current))
           :damage/reduce
             (let [base (double (:base current))
                   rate (double (or (value (:rate program) context) 0.0))
                   max-cost (double (or (value (:max-cost program) context) 0.0))
                   threshold (double (or (value (:ignore-threshold program) context)
                                         Double/POSITIVE_INFINITY))
                   cp (double (or (get-in state [:resources :cp]) 0.0))
                   cp-cost (min cp (max 0.0 max-cost))
                   exp-scale (double (or (value (:exp-scale program) context) 0.0))
                   valid? (and (Double/isFinite base) (pos? base)
                               (Double/isFinite rate) (<= 0.0 rate 1.0)
                               (Double/isFinite threshold) (<= base threshold))]
               (if-not valid?
                 current
                 (let [remaining (* base (- 1.0 rate))
                       ratio (if (pos? base) (/ remaining base) 0.0)
                       result (-> current
                                  (assoc :base remaining)
                                  (assoc :components (scale-components (:components current) ratio))
                                  (assoc-in [:metadata :resource-cost] {:cp (- cp-cost)})
                                  (assoc-in [:metadata :damage-reduction]
                                            {:rate rate :damage-ignore-threshold threshold})
                                  (update :state-patch (fnil into [])
                                          [[:resource :cp (- cp-cost)]
                                           [:ability-exp ability-id (* base exp-scale)]]))]
                   (if-let [vfx (:vfx program)]
                     (update result :vfx-signals (fnil conj [])
                             {:op :spawn :effect-id (:effect-id vfx :audio-one-shot)
                              :instance-key [ability-id :damage]
                              :owner target
                              :world-id (get-in context [:context :world-id])
                              :event-seq 0 :seed (long (or (:activation-seed context) 0))
                              :event :spawn
                              :params (merge {:position (or (get-in state [:position])
                                                             [0.0 0.0 0.0])}
                                              (dissoc vfx :effect-id))})
                     result))))
           :damage/absorb
             (let [base (double (:base current))
                   ticks (long (or (get-in session [:state :active-ticks]) 0))
                   last-tick (long (or (get-in session [:state (:last-tick-path program)]) -1))
                   interval (long (or (value (:interval-ticks program) context) 0))
                   cap (double (or (value (:cap program) context) 0.0))
                   front? (boolean (value (:front? program) context))
                   window? (and (pos? base)
                                (or (= -1 last-tick) (> (- ticks last-tick) interval)))
                   costs (into {} (map (fn [[r amount]]
                                         [r (double (or (value amount context) 0.0))])
                                       (:cost program)))
                   resources (or (:resources state) {})
                   enough? (every? (fn [[r amount]]
                                    (>= (double (or (get resources r) 0.0)) amount)) costs)
                   exp-scale (double (or (value (:exp-scale program) context) 0.0))
                   current (if window?
                             (update current :state-patch (fnil conj [])
                                     [:ability-exp ability-id exp-scale]) current)]
               (if (and window? front? enough? (Double/isFinite cap)
                        (<= 0.0 cap 100.0))
                 (let [absorbed (min base cap)
                       remaining (max 0.0 (- base absorbed))
                       defer? (and precheck? (pos? remaining))
                       ratio (if (pos? base) (/ remaining base) 0.0)]
                   (if defer?
                     current
                     (cond-> (-> current
                                 (assoc :base remaining)
                                 (assoc :components (scale-components (:components current) ratio))
                                 (assoc-in [:metadata :resource-cost]
                                           (into {} (map (fn [[r amount]] [r (- amount)]) costs)))
                                 (update :state-patch (fnil into [])
                                         (mapv (fn [[r amount]] [:resource r (- amount)]) costs))
                                 (update :session-patch (fnil conj [])
                                         {:path (:last-tick-path program) :mode :assign :value ticks}))
                       precheck? (assoc :cancelled? true))))
                 current))
             (let [multiplier (double (or (value (:multiplier program) context) 0.0))
                   cost-rate (double (or (value (:cost-per-damage program) context) 0.0))
                   minimum (double (or (value (:minimum program) context) 0.0))
                   max-depth (long (or (value (:max-depth program) context) 0))
                   exp-scale (double (or (value (:exp-scale program) context) 0.0))
                   depth (long (or (get-in current [:metadata :reflection-depth]) 0))
                   base (double (:base current))
                   reflected (* base multiplier (Math/pow 0.5 (double depth)))
                   cp (double (or (get-in state [:resources :cp]) 0.0))
                   consumption (max 0.0 (* base cost-rate))
                   source (:source current)
                   eligible? (and source (not= (str source) target)
                                  (Double/isFinite base) (pos? base)
                                  (Double/isFinite reflected) (>= reflected minimum)
                                  (< depth max-depth) (pos? consumption)
                                  (>= cp consumption))]
               (if-not eligible? current
                 (let [ratio (if (pos? base) (/ reflected base) 0.0)
                       reflected-request
                       (-> current
                           (assoc :source target :target source :base reflected)
                           (cond-> (vector? (:direction current))
                             (assoc :direction (mapv #(- (double %)) (:direction current))))
                           (assoc :components (scale-components (:components current) ratio))
                           (update :tags (fnil conj #{}) :reflected)
                           (update :metadata merge
                                   {:reflection-source? true
                                    :reflection-depth (inc depth)
                                    :reflection-ability ability-id}))]
                   (-> current
                       (assoc :base (max 0.0 (- base reflected)))
                       (assoc-in [:metadata :resource-cost] {:cp (- consumption)})
                       (update :state-patch (fnil conj [])
                               [:ability-exp ability-id (* base exp-scale)])
                       (update :world-effects (fnil conj [])
                               {:type :damage :request reflected-request})
                        (cond-> precheck? (assoc :cancelled? true)))))))))))
      request reactions)))

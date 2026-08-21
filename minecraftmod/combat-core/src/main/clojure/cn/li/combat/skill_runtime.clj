(ns cn.li.combat.skill-runtime
  "EDN ability orchestration owned by Combat Core.

   AC supplies only neutral snapshots and persistence callbacks.  This
   namespace owns phase selection, parameter/tunable materialization, VM
   execution, VFX ABI normalization and action commit ordering."
  (:require [cn.li.combat.frame :as frame]
            [cn.li.combat.host :as host]
            [cn.li.combat.vm :as vm]
            [cn.li.mcmod.runtime.capabilities :as capabilities]
            [cn.li.mcmod.runtime.vfx-contract :as vfx-contract]))

(set! *warn-on-reflection* true)

(defn- phase-of [intent]
  (or (:phase intent)
      (case (:action intent)
        :start :start :pulse :pulse :release :release :abort :abort
        :event :events :start)))

(defn- normalize-vfx-signal [owner ability-id execution-context signal]
  (let [operation (:operation signal)
        op (case operation :spawn :spawn :update :signal :destroy :destroy
             (throw (ex-info "unsupported EDN VFX operation"
                             {:operation operation :signal signal})))
        event-seq (long (or (:event-seq execution-context)
                            (:server-tick execution-context) 1))
        instance-key (or (:instance-key signal)
                         [ability-id :activation (:effect-id signal)])
        world-id (or (get-in execution-context [:from :world/id])
                     (get-in execution-context [:context :world-id]))]
    (vfx-contract/signal
     {:op op :effect-id (:effect-id signal) :instance-key instance-key
      :owner owner :world-id world-id :event-seq event-seq
      :seed (long (or (:activation-seed execution-context) 0))
      :event (when (#{:spawn :update} operation) operation)
      :params (or (:payload signal) {})})))

(defn- parameter-snapshot [ability-id ability intent]
  (if (contains? intent :parameter-snapshot)
    (:parameter-snapshot intent)
    (into {}
          (map (fn [[parameter-id declaration]]
                 (when-not (contains? declaration :value)
                   (throw (ex-info "EDN parameter was not materialized"
                                   {:ability-id ability-id
                                    :parameter parameter-id})))
                 [parameter-id (:value declaration)])
               (:parameters ability)))))

(defn- lerp [lo hi t]
  (let [t (max 0.0 (min 1.0 (double t)))]
    (+ (double lo) (* (- (double hi) (double lo)) t))))

(defn materialize-tunables [ability skill-exp]
  (reduce-kv
   (fn [result tunable-id {:keys [curve value range]}]
     (assoc result tunable-id
            (case curve
              :const value
              :pair value
              :mastery-lerp (lerp (first range) (second range) skill-exp)
              :affine (+ (double (first range))
                         (* (double (second range)) (double skill-exp)))
              (throw (ex-info "unsupported tunable curve at activation"
                              {:tunable tunable-id :curve curve})))))
   {} (or (:tunables ability) {})))

(defn execute!
  [catalog ability-id owner intent]
  (let [ability (get-in catalog [:combat :abilities ability-id])]
    (when-not ability
      (throw (ex-info "ability is not compiled" {:ability-id ability-id})))
    (let [program (:compiled-program ability)
          execution-frame (frame/create-frame (:slot-counts ability))
          capability-state (capabilities/snapshot)
          host (host/build-host-table-from-capabilities capability-state)
          query-order (vec (sort (keys (:queries capability-state))))
          results* (volatile! {})
          latches* (volatile! (set (or (:latches intent) #{})))
          slots* (volatile! {})
          rng-counter* (volatile! 0)
          resources* (volatile! (or (get-in intent [:context :resources]) {}))
          execution-context (assoc
                            {:owner owner :ability-id ability-id
                             :phase (phase-of intent)
                             :activation-seed (long (or (:activation-seed intent) 0))
                             :server-tick (long (or (:server-tick intent) 0))
                             :event-seq (long (or (:event-seq intent) 1))
                             :event (:event intent) :intent intent
                             :context (merge {:owner owner :ability-id ability-id
                                              ;; Manifest aliases may carry
                                              ;; declarative visual/asset
                                              ;; variants.  They are exposed
                                              ;; through the neutral ability
                                              ;; runtime bag; no skill code is
                                              ;; needed in AC or Combat Core.
                                              :ability-runtime (:runtime ability)}
                                             (:context intent))
                             :session-state (:session-state intent)
                             :params (or (:parameter-snapshot intent)
                                         (:parameters intent)
                                         (:parameters ability) {})
                             :from (or (:from intent) {})
                             :tunables (or (:tunables intent) {})
                             :costs (or (:costs intent) (:costs ability))
                             :progression (or (:progression intent)
                                              (:progression ability))
                             :cooldown (or (:cooldown intent) (:cooldown ability))
                             :invariants (or (:invariants intent)
                                             (:invariants ability))
                             :query-order query-order :results* results*
                             :latches* latches* :slots* slots*
                             :rng-counter* rng-counter*}
                            :resources* resources*)
          result (vm/execute! program execution-frame host 0 execution-context)]
      (assoc result
             :schema-version 2 :ability-id ability-id :owner owner
             :vfx-signals (mapv #(normalize-vfx-signal owner ability-id
                                                        execution-context %)
                                (:vfx result))
             :actions (cond-> (vec (:actions result))
                        (seq @latches*)
                        (conj {:type :session-latches :latches @latches*}))
             :events (vec (:events result)) :query-results @results*
             :status (if (= :finished (:status result)) :accepted
                         (:status result))))))

(defn commit-actions!
  [owner actions action-handlers]
  (mapv (fn [action]
          (let [capability (:capability action)
                handler (get action-handlers capability)
                request (assoc action :owner owner)]
            (if (and (= :block/set capability)
                     (not (and (vector? (:expected-block-ids request))
                               (seq (:expected-block-ids request))
                               (<= (count (:expected-block-ids request)) 8)
                               (string? (:block-id request))
                               (let [position (:position request)
                                     point (if (and (map? position)
                                                    (vector? (:vec3 position)))
                                             (:vec3 position) position)]
                                 (and (vector? point) (= 3 (count point))
                                      (every? number? point))))))
              {:status :rejected :capability capability
               :reason :invalid-bounded-block-set :request request}
              (if-not handler
                {:status :unhandled :capability capability :request request}
                (try
                  {:status :committed :capability capability
                   :result (handler request)}
                  (catch Throwable throwable
                    {:status :failed :capability capability
                     :message (ex-message throwable)}))))))
        actions))

(defn dispatch!
  "Run one EDN lifecycle operation using neutral AC persistence callbacks.

   `session-port` contains :current, :start!, :context, :apply-actions! and
   :remove! functions.  `activation-context-fn` and `caster-facade-fn` are
   neutral snapshot providers; Combat Core owns all EDN decisions around
   them, including cooldown gating -- AC never inspects cooldown state
   itself, it only projects it into `:cooldowns` on the owner view."
  [{:keys [catalog owner intent session-port owner-view-fn
           activation-context-fn caster-facade-fn now-tick-fn seed-fn]}]
  (let [ability-id (or (:ability-id intent) (:ability intent)
                       (some-> ((:resolve-slot-fn session-port) owner intent) :id))
        ability (get-in catalog [:combat :abilities ability-id])
        current-tick (long (or (:server-tick intent) (now-tick-fn)))
        active-session ((:current session-port) owner)
        requested-op (or (:action intent) (:op intent) :start)
        ;; toggle-close? must be resolved before the cooldown gate: closing
        ;; an already-active toggle is an :abort, which the gate never
        ;; blocks, even while its own cooldown (started at the prior
        ;; :release) is still counting down.
        toggle-close? (and (= :start requested-op) active-session
                           (= :toggle (:activation ability))
                           (= ability-id (:ability-id active-session)))
        op (if toggle-close? :abort requested-op)
        owner-view (owner-view-fn owner)
        cooldown-remaining (when (= :start op)
                             (some->> (get (:cooldowns owner-view) ability-id)
                                      vals (filter pos?) seq (apply max)))]
    (if cooldown-remaining
      {:schema-version 2 :status :rejected :reason :on-cooldown
       :ability-id ability-id :owner owner
       :feedback [{:type :on-cooldown :ability-id ability-id
                   :remaining-ticks (long cooldown-remaining)}]}
      (let [activation-seed (or (:activation-seed active-session)
                                (:activation-seed intent)
                                (seed-fn owner ability-id current-tick))
            normalized (assoc intent :action op :ability-id ability-id
                              :activation-seed activation-seed
                              :context (or (:context active-session)
                                           (activation-context-fn owner ability-id
                                                                  intent activation-seed))
                              :parameter-snapshot
                              (or (:parameter-snapshot intent)
                                  (:parameter-snapshot active-session)
                                  (parameter-snapshot ability-id ability intent)))]
        (when (= :start op)
          ((:start! session-port) owner ability-id normalized))
        (let [session ((:current session-port) owner)
              session-context ((:context session-port) owner normalized)
              start-tick (long (or (:start-tick session) current-tick))
              dynamic-context (merge (:context session-context)
                                     {:server-tick current-tick
                                      :session-start-tick start-tick
                                      :resources (:resources owner-view)
                                      :skill-exp (double (or (get-in owner-view
                                                                   [:ability-data :skill-exps ability-id]) 0.0))
                                      :hold-ticks (max 0 (- current-tick start-tick))})
              execution-intent (merge normalized
                                      (select-keys session-context
                                                   [:context :parameter-snapshot
                                                    :activation-seed])
                                      {:context dynamic-context
                                       :session-state (:state session-context)
                                       :latches (:latches session-context)
                                       :server-tick current-tick
                                       :from (caster-facade-fn owner dynamic-context)
                                       :tunables (materialize-tunables
                                                  ability (:skill-exp dynamic-context))
                                       :costs (:costs ability)
                                       :progression (:progression ability)
                                       :cooldown (:cooldown ability)
                                       :invariants (:invariants ability)})
              result (execute! catalog ability-id owner execution-intent)]
          (when (= :accepted (:status result))
            ((:apply-actions! session-port) owner (:actions result)))
          (when (or (:finish-session? result)
                    (= :instant (:activation ability))
                    (and (#{:release :abort} op)
                         (not= true (:release-keeps-session? ability))
                         (#{:accepted :rejected} (:status result))))
            ((:remove! session-port) owner))
          result)))))

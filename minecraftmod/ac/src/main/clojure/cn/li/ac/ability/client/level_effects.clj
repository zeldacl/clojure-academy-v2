(ns cn.li.ac.ability.client.level-effects
  "Registry-based level effect infrastructure for client-side ability visuals.

  Skills register their effect handlers via `register-level-effect!` at load
  time.  The infrastructure dispatches enqueue / tick / build-plan calls to
  the registered handlers without any skill-specific knowledge.

  State stored in Framework [:service :level-effects]."
  (:require [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.util.log :as log]))

;; ---------------------------------------------------------------------------
;; Registry — Framework [:service :level-effects]
;; ---------------------------------------------------------------------------

(defn default-level-effect-runtime-state []
  {:registry {} :order [] :effect-states {} :frozen? false})

(def ^:private le-path [:service :level-effects])

(defn- level-effect-state-atom []
  (if-let [fw-atom (fw/fw-atom)]
    (or (get-in @fw-atom le-path)
        (let [a (atom (default-level-effect-runtime-state))]
          (swap! fw-atom assoc-in le-path a) a))
    (atom (default-level-effect-runtime-state))))

;; Backward-compatible factory
(defn create-level-effect-runtime
  ([]
   (create-level-effect-runtime {}))
  ([{:keys [state*]
     :or {state* (level-effect-state-atom)}}]
   {::runtime ::level-effect-runtime :state* state*}))

(defn- level-effect-state-snapshot [] @(level-effect-state-atom))

(defn- update-level-effect-state! [f & args]
  (apply swap! (level-effect-state-atom) f args))

(defn- assoc-effect-state [state effect-id effect-state]
  (if (nil? effect-state)
    (update state :effect-states dissoc effect-id)
    (assoc-in state [:effect-states effect-id] effect-state)))

(defn- assert-registry-open! []
  (when (:frozen? (level-effect-state-snapshot))
    (throw (ex-info "Level effect registry is frozen" {}))))

(defn register-level-effect! [effect-id handler-map]
  (when-not (and (keyword? effect-id) (map? handler-map)
                 (or (fn? (:enqueue-fn handler-map))
                     (fn? (:enqueue-event-fn handler-map))
                     (fn? (:enqueue-state-fn handler-map)))
                 (or (fn? (:tick-fn handler-map))
                     (fn? (:tick-state-fn handler-map)))
                 (fn? (:build-plan-fn handler-map)))
    (throw (IllegalArgumentException. "register-level-effect!: invalid effect-id or handler-map")))
  (assert-registry-open!)
  (update-level-effect-state!
    (fn [state]
      (if (get-in state [:registry effect-id]) state
        (-> state (update :order conj effect-id)
            (assoc-in [:registry effect-id] handler-map)
            (assoc-effect-state effect-id (:initial-state handler-map))))))
  (log/debug "Registered level effect:" effect-id) nil)

(defn freeze-level-effect-registry! []
  (update-level-effect-state! assoc :frozen? true) nil)

(defn level-effect-registry-snapshot [] (level-effect-state-snapshot))

(defn effect-state-snapshot [effect-id]
  (get-in (level-effect-state-snapshot) [:effect-states effect-id]))

(defn reset-level-effect-state-for-test! [effect-id state]
  (update-level-effect-state! assoc-effect-state effect-id state) nil)

(defn update-effect-state! [effect-id f & args]
  (update-level-effect-state!
    (fn [state] (let [current-state (get-in state [:effect-states effect-id])
                      next-state (apply f current-state args)]
                  (assoc-effect-state state effect-id next-state)))) nil)

(defn reset-level-effect-registry-for-test! []
  (reset! (level-effect-state-atom) (default-level-effect-runtime-state)) nil)

;; ---------------------------------------------------------------------------
;; Dispatch
;; ---------------------------------------------------------------------------

(defn- default-owner-key [effect-id payload ctx-id channel]
  (cond (and (map? payload) (:effect-instance-id payload)) [:effect-instance (:effect-instance-id payload)]
        ctx-id [:ctx ctx-id]
        (and (map? payload) (:source-player-id payload)) [:source-player (:source-player-id payload)]
        (and (map? payload) (:player-id payload)) [:player (:player-id payload)]
        channel [:channel channel]
        :else [:effect effect-id :global]))

(defn- enqueue-event [effect-id payload fx-context]
  (let [{:keys [ctx-id channel owner-key]} fx-context]
    (assoc (or fx-context {}) :effect-id effect-id :payload payload
           :ctx-id ctx-id :channel channel
           :owner-key (or owner-key (default-owner-key effect-id payload ctx-id channel)))))

(defn enqueue-level-effect!
  ([effect-id payload] (enqueue-level-effect! effect-id payload nil))
  ([effect-id payload fx-context]
   (if-let [{:keys [enqueue-fn enqueue-event-fn enqueue-state-fn]}
            (get-in (level-effect-state-snapshot) [:registry effect-id])]
     (cond enqueue-state-fn
           (let [event (enqueue-event effect-id payload fx-context)]
             (update-level-effect-state!
               (fn [state] (let [current-state (get-in state [:effect-states effect-id])
                                 next-state (enqueue-state-fn current-state event)]
                             (assoc-effect-state state effect-id next-state)))))
           enqueue-event-fn (enqueue-event-fn (enqueue-event effect-id payload fx-context))
           :else (enqueue-fn payload))
     (log/warn "No level effect registered for" effect-id))))

(defn tick-level-effects! []
  (let [{:keys [order registry]} (level-effect-state-snapshot)]
    (doseq [eid order]
      (when-let [{:keys [tick-fn tick-state-fn]} (get registry eid)]
        (if tick-state-fn
          (update-level-effect-state!
            (fn [state] (let [current-state (get-in state [:effect-states eid])
                              next-state (tick-state-fn current-state)]
                          (assoc-effect-state state eid next-state))))
          (tick-fn))))))

(defn- invoke-build-plan-fn [build-plan-fn camera-pos hand-center-pos tick frame-context]
  (try (build-plan-fn camera-pos hand-center-pos tick frame-context)
       (catch clojure.lang.ArityException _ (build-plan-fn camera-pos hand-center-pos tick))))

(defn build-level-effect-plan
  ([camera-pos hand-center-pos tick] (build-level-effect-plan camera-pos hand-center-pos tick nil))
  ([camera-pos hand-center-pos tick frame-context]
   (let [{:keys [order registry]} (level-effect-state-snapshot)
         results (keep (fn [eid] (when-let [{:keys [build-plan-fn]} (get registry eid)]
                                   (invoke-build-plan-fn build-plan-fn camera-pos hand-center-pos tick frame-context))) order)
         all-ops (into [] (mapcat :ops) results)
         walk-speeds (keep :local-walk-speed results)
         local-walk-speed (when (seq walk-speeds) (float (apply min walk-speeds)))]
     (when (or (seq all-ops) local-walk-speed) {:ops all-ops :local-walk-speed local-walk-speed}))))

(defn registered-effects []
  (set (keys (:registry (level-effect-state-snapshot)))))

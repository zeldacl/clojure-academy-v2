(ns cn.li.ac.ability.client.level-effects
  "Registry-based level effect infrastructure for client-side ability visuals.

  Skills register their effect handlers via `register-level-effect!` at load
  time.  The infrastructure dispatches enqueue / tick / build-plan calls to
  the registered handlers without any skill-specific knowledge."
  (:require [cn.li.mcmod.util.log :as log]))

;; ---------------------------------------------------------------------------
;; Registry
;; ---------------------------------------------------------------------------

;; effect-id 鈫?{:enqueue-fn        (fn [payload])
;;              :enqueue-event-fn  (fn [{:keys [effect-id payload ctx-id channel owner-key]}])
;;              :tick-fn        (fn [])
;;              :build-plan-fn  (fn [camera-pos hand-center-pos tick])
;;                              or (fn [camera-pos hand-center-pos tick frame-context])
;;                              鈫?seq of ops | nil
;;              :walk-speed-fn  (fn []) 鈫?float | nil          (optional)}
(defonce ^:private effect-registry (atom {}))

;; Ordered list of effect-ids, preserving registration order for deterministic
;; plan concatenation.
(defonce ^:private effect-order (atom []))
(defonce ^:private effect-registry-frozen? (atom false))

(defn- assert-registry-open!
  []
  (when @effect-registry-frozen?
    (throw (ex-info "Level effect registry is frozen" {}))))

(defn register-level-effect!
  "Register a level effect handler.  `effect-id` is a keyword.

  `handler-map` keys:
    :enqueue-fn        (fn [payload]) 鈥?process incoming FX data
    :enqueue-event-fn  (fn [{:keys [effect-id payload ctx-id channel owner-key]}])
                       鈥?process incoming FX data with owner metadata
    :tick-fn       (fn []) 鈥?advance animation state each game tick
    :build-plan-fn (fn [camera-pos hand-center-pos tick]) or
             (fn [camera-pos hand-center-pos tick frame-context])
             鈫?{:ops [...]} or nil
    :walk-speed-fn (fn []) 鈫?float or nil  (optional)"
  [effect-id handler-map]
  {:pre [(keyword? effect-id) (map? handler-map)
         (or (fn? (:enqueue-fn handler-map))
             (fn? (:enqueue-event-fn handler-map)))
         (fn? (:tick-fn handler-map))
         (fn? (:build-plan-fn handler-map))]}
  (assert-registry-open!)
  (when-not (get @effect-registry effect-id)
    (swap! effect-order conj effect-id)
    (swap! effect-registry assoc effect-id handler-map))
  (log/debug "Registered level effect:" effect-id)
  nil)

(defn freeze-level-effect-registry!
  []
  (reset! effect-registry-frozen? true)
  nil)

(defn level-effect-registry-snapshot
  []
  {:registry @effect-registry
   :order @effect-order
   :frozen? @effect-registry-frozen?})

(defn reset-level-effect-registry-for-test!
  []
  (reset! effect-registry {})
  (reset! effect-order [])
  (reset! effect-registry-frozen? false)
  nil)

;; ---------------------------------------------------------------------------
;; Dispatch
;; ---------------------------------------------------------------------------

(defn- default-owner-key
  [effect-id payload ctx-id channel]
  (cond
    (and (map? payload) (:effect-instance-id payload))
    [:effect-instance (:effect-instance-id payload)]

    ctx-id
    [:ctx ctx-id]

    (and (map? payload) (:source-player-id payload))
    [:source-player (:source-player-id payload)]

    (and (map? payload) (:player-id payload))
    [:player (:player-id payload)]

    channel
    [:channel channel]

    :else
    [:effect effect-id :global]))

(defn- enqueue-event
  [effect-id payload fx-context]
  (let [{:keys [ctx-id channel owner-key]} fx-context]
    (assoc (or fx-context {})
           :effect-id effect-id
           :payload payload
           :ctx-id ctx-id
           :channel channel
           :owner-key (or owner-key
                          (default-owner-key effect-id payload ctx-id channel)))))

(defn enqueue-level-effect!
  "Dispatch an incoming FX payload to the registered effect handler."
  ([effect-id payload]
   (enqueue-level-effect! effect-id payload nil))
  ([effect-id payload fx-context]
   (if-let [{:keys [enqueue-fn enqueue-event-fn]} (get @effect-registry effect-id)]
     (if enqueue-event-fn
       (enqueue-event-fn (enqueue-event effect-id payload fx-context))
       (enqueue-fn payload))
     (log/warn "No level effect registered for" effect-id))))

(defn tick-level-effects!
  "Tick all registered level effects."
  []
  (doseq [eid @effect-order]
    (when-let [{:keys [tick-fn]} (get @effect-registry eid)]
      (tick-fn))))

(defn- invoke-build-plan-fn
  [build-plan-fn camera-pos hand-center-pos tick frame-context]
  (try
    (build-plan-fn camera-pos hand-center-pos tick frame-context)
    (catch clojure.lang.ArityException _
      (build-plan-fn camera-pos hand-center-pos tick))))

(defn build-level-effect-plan
  "Build the combined render plan from all registered effects.
  Returns {:ops [...] :local-walk-speed float} or nil when nothing to render."
  ([camera-pos hand-center-pos tick]
   (build-level-effect-plan camera-pos hand-center-pos tick nil))
  ([camera-pos hand-center-pos tick frame-context]
   (let [results (keep (fn [eid]
                         (when-let [{:keys [build-plan-fn]} (get @effect-registry eid)]
                           (invoke-build-plan-fn build-plan-fn
                                                 camera-pos
                                                 hand-center-pos
                                                 tick
                                                 frame-context)))
                       @effect-order)
         all-ops (into [] (mapcat :ops) results)
         walk-speeds (keep :local-walk-speed results)
         local-walk-speed (when (seq walk-speeds)
                            (float (apply min walk-speeds)))]
     (when (or (seq all-ops) local-walk-speed)
       {:ops all-ops
        :local-walk-speed local-walk-speed}))))

;; ---------------------------------------------------------------------------
;; Introspection (diagnostics)
;; ---------------------------------------------------------------------------

(defn registered-effects
  "Return the set of currently-registered effect ids."
  []
  (set (keys @effect-registry)))

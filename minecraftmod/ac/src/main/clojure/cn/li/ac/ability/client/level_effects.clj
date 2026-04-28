(ns cn.li.ac.ability.client.level-effects
  "Registry-based level effect infrastructure for client-side ability visuals.

  Skills register their effect handlers via `register-level-effect!` at load
  time.  The infrastructure dispatches enqueue / tick / build-plan calls to
  the registered handlers without any skill-specific knowledge."
  (:require [cn.li.mcmod.util.log :as log]))

;; ---------------------------------------------------------------------------
;; Registry
;; ---------------------------------------------------------------------------

;; effect-id 鈫?{:enqueue-fn    (fn [payload])
;;              :tick-fn        (fn [])
;;              :build-plan-fn  (fn [camera-pos hand-center-pos tick]) 鈫?seq of ops | nil
;;              :walk-speed-fn  (fn []) 鈫?float | nil          (optional)}
(defonce ^:private effect-registry (atom {}))

;; Ordered list of effect-ids, preserving registration order for deterministic
;; plan concatenation.
(defonce ^:private effect-order (atom []))

(defn register-level-effect!
  "Register a level effect handler.  `effect-id` is a keyword.

  `handler-map` keys:
    :enqueue-fn    (fn [payload]) 鈥?process incoming FX data
    :tick-fn       (fn []) 鈥?advance animation state each game tick
    :build-plan-fn (fn [camera-pos hand-center-pos tick]) 鈫?{:ops [...]} or nil
    :walk-speed-fn (fn []) 鈫?float or nil  (optional)"
  [effect-id handler-map]
  {:pre [(keyword? effect-id) (map? handler-map)
         (fn? (:enqueue-fn handler-map))
         (fn? (:tick-fn handler-map))
         (fn? (:build-plan-fn handler-map))]}
  (when-not (get @effect-registry effect-id)
    (swap! effect-order conj effect-id))
  (swap! effect-registry assoc effect-id handler-map)
  (log/debug "Registered level effect:" effect-id)
  nil)

;; ---------------------------------------------------------------------------
;; Dispatch
;; ---------------------------------------------------------------------------

(defn enqueue-level-effect!
  "Dispatch an incoming FX payload to the registered effect handler."
  [effect-id payload]
  (if-let [{:keys [enqueue-fn]} (get @effect-registry effect-id)]
    (enqueue-fn payload)
    (log/warn "No level effect registered for" effect-id)))

(defn tick-level-effects!
  "Tick all registered level effects."
  []
  (doseq [eid @effect-order]
    (when-let [{:keys [tick-fn]} (get @effect-registry eid)]
      (tick-fn))))

(defn build-level-effect-plan
  "Build the combined render plan from all registered effects.
  Returns {:ops [...] :local-walk-speed float} or nil when nothing to render."
  [camera-pos hand-center-pos tick]
  (let [results (keep (fn [eid]
                        (when-let [{:keys [build-plan-fn]} (get @effect-registry eid)]
                          (build-plan-fn camera-pos hand-center-pos tick)))
                      @effect-order)
        all-ops (into [] (mapcat :ops) results)
        walk-speeds (keep :local-walk-speed results)
        local-walk-speed (when (seq walk-speeds)
                           (float (apply min walk-speeds)))]
    (when (or (seq all-ops) local-walk-speed)
      {:ops all-ops
       :local-walk-speed local-walk-speed})))

;; ---------------------------------------------------------------------------
;; Introspection (diagnostics)
;; ---------------------------------------------------------------------------

(defn registered-effects
  "Return the set of currently-registered effect ids."
  []
  (set (keys @effect-registry)))

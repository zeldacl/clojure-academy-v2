(ns cn.li.ac.ability.client.level-effects
  "Registry-based level effect infrastructure for client-side ability visuals.

  Skills register their effect handlers via `register-level-effect!` at load
  time.  The infrastructure dispatches enqueue / tick / build-plan calls to
  the registered handlers without any skill-specific knowledge."
  (:require [cn.li.mcmod.util.log :as log]))

;; ---------------------------------------------------------------------------
;; Registry
;; ---------------------------------------------------------------------------

(defn default-level-effect-runtime-state
  []
  {:registry {}
   :order []
  :effect-states {}
   :frozen? false})

(defn create-level-effect-runtime
  ([]
   (create-level-effect-runtime {}))
  ([{:keys [state*]
     :or {state* (atom (default-level-effect-runtime-state))}}]
   {::runtime ::level-effect-runtime
    :state* state*}))

(def ^:dynamic *level-effect-runtime* nil)

(def ^:private _level-effect-runtime (delay (create-level-effect-runtime)))

(defn- level-effect-runtime?
  [runtime]
  (and (map? runtime)
       (= ::level-effect-runtime (::runtime runtime))
       (some? (:state* runtime))))

(defn call-with-level-effect-runtime
  [runtime f]
  (when-not (level-effect-runtime? runtime)
    (throw (ex-info "Expected level-effect runtime"
                    {:value runtime})))
  (binding [*level-effect-runtime* runtime]
    (f)))

(defmacro with-level-effect-runtime
  [runtime & body]
  `(call-with-level-effect-runtime ~runtime (fn [] ~@body)))

(defn- current-level-effect-runtime
  []
  (or *level-effect-runtime*
      @_level-effect-runtime))

(defn- level-effect-state-atom
  []
  (:state* (current-level-effect-runtime)))

(defn- level-effect-state-snapshot
  []
  @(level-effect-state-atom))

(defn- update-level-effect-state!
  [f & args]
  (apply swap! (level-effect-state-atom) f args))

(defn- assoc-effect-state
  [state effect-id effect-state]
  (if (nil? effect-state)
    (update state :effect-states dissoc effect-id)
    (assoc-in state [:effect-states effect-id] effect-state)))

;; effect-id 鈫?{:enqueue-fn        (fn [payload])
;;              :enqueue-event-fn  (fn [{:keys [effect-id payload ctx-id channel owner-key]}])
;;              :tick-fn        (fn [])
;;              :build-plan-fn  (fn [camera-pos hand-center-pos tick])
;;                              or (fn [camera-pos hand-center-pos tick frame-context])
;;                              鈫?seq of ops | nil
;;              :walk-speed-fn  (fn []) 鈫?float | nil          (optional)}

(defn- assert-registry-open!
  []
  (when (:frozen? (level-effect-state-snapshot))
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
      (if (get-in state [:registry effect-id])
        state
        (-> state
            (update :order conj effect-id)
            (assoc-in [:registry effect-id] handler-map)
            (assoc-effect-state effect-id (:initial-state handler-map))))))
  (log/debug "Registered level effect:" effect-id)
  nil)

(defn freeze-level-effect-registry!
  []
  (update-level-effect-state! assoc :frozen? true)
  nil)

(defn level-effect-registry-snapshot
  []
  (level-effect-state-snapshot))

(defn effect-state-snapshot
  [effect-id]
  (get-in (level-effect-state-snapshot) [:effect-states effect-id]))

(defn reset-level-effect-state-for-test!
  [effect-id state]
  (update-level-effect-state! assoc-effect-state effect-id state)
  nil)

(defn update-effect-state!
  [effect-id f & args]
  (update-level-effect-state!
    (fn [state]
      (let [current-state (get-in state [:effect-states effect-id])
            next-state (apply f current-state args)]
        (assoc-effect-state state effect-id next-state))))
  nil)

(defn reset-level-effect-registry-for-test!
  []
  (reset! (level-effect-state-atom) (default-level-effect-runtime-state))
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
   (if-let [{:keys [enqueue-fn enqueue-event-fn enqueue-state-fn]} (get-in (level-effect-state-snapshot) [:registry effect-id])]
     (cond
       enqueue-state-fn
       (let [event (enqueue-event effect-id payload fx-context)]
         (update-level-effect-state!
           (fn [state]
             (let [current-state (get-in state [:effect-states effect-id])
                   next-state (enqueue-state-fn current-state event)]
               (assoc-effect-state state effect-id next-state)))))

       enqueue-event-fn
       (enqueue-event-fn (enqueue-event effect-id payload fx-context))

       :else
       (enqueue-fn payload))
     (log/warn "No level effect registered for" effect-id))))

(defn tick-level-effects!
  "Tick all registered level effects."
  []
  (let [{:keys [order registry]} (level-effect-state-snapshot)]
    (doseq [eid order]
      (when-let [{:keys [tick-fn tick-state-fn]} (get registry eid)]
        (if tick-state-fn
          (update-level-effect-state!
            (fn [state]
              (let [current-state (get-in state [:effect-states eid])
                    next-state (tick-state-fn current-state)]
                (assoc-effect-state state eid next-state))))
          (tick-fn))))))

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
   (let [{:keys [order registry]} (level-effect-state-snapshot)
         results (keep (fn [eid]
                         (when-let [{:keys [build-plan-fn]} (get registry eid)]
                           (invoke-build-plan-fn build-plan-fn
                                                 camera-pos
                                                 hand-center-pos
                                                 tick
                                                 frame-context)))
                       order)
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
  (set (keys (:registry (level-effect-state-snapshot)))))

(ns cn.li.ac.ability.client.hand-effects
  "Registry-based hand effect infrastructure for first-person hand animations.

  Skills register their hand-effect handlers via `register-hand-effect!` at
  load time.  The infrastructure dispatches enqueue / tick / transform calls
  without any skill-specific knowledge."
  (:require [cn.li.ac.ability.client.effects.queue-infra :as queue-infra]
            [cn.li.mcmod.util.log :as log]))

;; ---------------------------------------------------------------------------
;; Camera pitch delta accumulator (shared across all hand effects)
;; ---------------------------------------------------------------------------

(defn create-camera-pitch-runtime
  []
  {::runtime ::camera-pitch-runtime
   :camera-pitch-deltas* (atom {})})

(def ^:dynamic *camera-pitch-runtime* nil)

(let [_instance (volatile! nil)]
  (defn- camera-pitch-instance []
    (or @_instance (vreset! _instance (create-camera-pitch-runtime)) @_instance)))

(defn- camera-pitch-runtime?
  [runtime]
  (and (map? runtime)
       (= ::camera-pitch-runtime (::runtime runtime))
       (some? (:camera-pitch-deltas* runtime))))

(defn call-with-camera-pitch-runtime
  [runtime f]
  (when-not (camera-pitch-runtime? runtime)
    (throw (ex-info "Expected camera pitch runtime"
                    {:runtime runtime})))
  (binding [*camera-pitch-runtime* runtime]
    (f)))

(defmacro with-camera-pitch-runtime
  [runtime & body]
  `(call-with-camera-pitch-runtime ~runtime (fn [] ~@body)))

(defn- current-camera-pitch-runtime
  []
  (or *camera-pitch-runtime*
      (camera-pitch-instance)))

(defn- camera-pitch-deltas-atom
  []
  (:camera-pitch-deltas* (current-camera-pitch-runtime)))

(defn- camera-pitch-deltas-snapshot
  []
  @(camera-pitch-deltas-atom))

(defn- update-camera-pitch-deltas!
  [f & args]
  (apply swap! (camera-pitch-deltas-atom) f args))

(defn- normalize-session-id
  [owner-or-session]
  (queue-infra/normalize-session-id "hand" owner-or-session))

(defn current-effect-owner
  []
  (queue-infra/current-effect-owner "hand"))

(defn add-camera-pitch-delta!
  "Queue a camera pitch delta (float degrees) to be consumed by the platform
  renderer on the next frame."
  ([delta]
   (add-camera-pitch-delta! nil delta))
  ([owner-or-session delta]
  (queue-infra/queue-effect! (camera-pitch-deltas-atom) "hand" owner-or-session (float delta))
   nil))

(defn add-current-camera-pitch-delta!
  [delta]
  (add-camera-pitch-delta! (current-effect-owner) delta))

(defn drain-camera-pitch-deltas!
  "Atomically drain and return all queued camera pitch deltas."
  ([]
   (drain-camera-pitch-deltas! nil))
  ([owner-or-session]
   (queue-infra/poll-effects! (camera-pitch-deltas-atom) "hand" owner-or-session)))

(defn clear-session-camera-pitch-deltas!
  ([]
   (clear-session-camera-pitch-deltas! nil))
  ([owner-or-session]
  (update-camera-pitch-deltas! dissoc (normalize-session-id owner-or-session))
   nil))

(defn clear-owner-camera-pitch-deltas!
  [owner]
  (clear-session-camera-pitch-deltas! owner))

;; ---------------------------------------------------------------------------
;; Utility functions (shared by skill hand-effect impls)
;; ---------------------------------------------------------------------------

(defn smoothstep
  "Hermite interpolation: 0 at edge0, 1 at edge1."
  ^double [^double edge0 ^double edge1 ^double x]
  (let [t (max 0.0 (min 1.0 (/ (- x edge0) (- edge1 edge0))))]
    (* t t (- 3.0 (* 2.0 t)))))

(defn sample-curve
  "Piecewise-linear interpolation on a sorted seq of [x y] control points."
  ^double [curve ^double t]
  (cond
    (<= t (ffirst curve)) (second (first curve))
    (>= t (first (last curve))) (second (last curve))
    :else
    (let [[a b] (first (filter (fn [[[x0 _] [x1 _]]]
                                 (and (<= x0 t) (< t x1)))
                               (partition 2 1 curve)))]
      (if (and a b)
        (let [[x0 y0] a
              [x1 y1] b
              frac (/ (- t x0) (- x1 x0))]
          (+ y0 (* frac (- y1 y0))))
        (second (last curve))))))

;; ---------------------------------------------------------------------------
;; Registry
;; ---------------------------------------------------------------------------

(defn default-hand-effect-runtime-state
  []
  {:registry {}
   :order []
  :effect-states {}
   :frozen? false})

(defn create-hand-effect-runtime
  ([]
   (create-hand-effect-runtime {}))
  ([{:keys [state*]
     :or {state* (atom (default-hand-effect-runtime-state))}}]
   {::runtime ::hand-effect-runtime
    :state* state*}))

(def ^:dynamic *hand-effect-runtime* nil)

(let [_instance (volatile! nil)]
  (defn- hand-effect-instance []
    (or @_instance (vreset! _instance (create-hand-effect-runtime)) @_instance)))

(defn- hand-effect-runtime?
  [runtime]
  (and (map? runtime)
       (= ::hand-effect-runtime (::runtime runtime))
       (some? (:state* runtime))))

(defn call-with-hand-effect-runtime
  [runtime f]
  (when-not (hand-effect-runtime? runtime)
    (throw (ex-info "Expected hand-effect runtime"
                    {:runtime runtime})))
  (binding [*hand-effect-runtime* runtime]
    (f)))

(defmacro with-hand-effect-runtime
  [runtime & body]
  `(call-with-hand-effect-runtime ~runtime (fn [] ~@body)))

(defn- current-hand-effect-runtime
  []
  (or *hand-effect-runtime*
      (hand-effect-instance)))

(defn- hand-effect-state-atom
  []
  (:state* (current-hand-effect-runtime)))

(defn- hand-effect-state-snapshot
  []
  @(hand-effect-state-atom))

(defn- update-hand-effect-state!
  [f & args]
  (apply swap! (hand-effect-state-atom) f args))

(defn- assoc-effect-state
  [state effect-id effect-state]
  (if (nil? effect-state)
    (update state :effect-states dissoc effect-id)
    (assoc-in state [:effect-states effect-id] effect-state)))

;; effect-id �?{:enqueue-fn        (fn [payload])
;;              :enqueue-state-fn  (fn [state payload] -> state)
;;              :tick-fn           (fn [])
;;              :tick-state-fn     (fn [state] -> state)
;;              :initial-state     any (optional)
;;              :transform-fn   (fn []) �?transform-map or nil  (optional)}

(defn- assert-registry-open!
  []
  (when (:frozen? (hand-effect-state-snapshot))
    (throw (ex-info "Hand effect registry is frozen" {}))))

(defn register-hand-effect!
  "Register a hand effect handler.  `effect-id` is a keyword.

  `handler-map` keys:
    :enqueue-fn   (fn [payload]) �?process incoming FX data
    :enqueue-state-fn (fn [state payload]) -> state
    :tick-fn      (fn []) �?advance animation state each game tick
    :tick-state-fn (fn [state]) -> state
    :initial-state any initial state for :enqueue-state-fn/:tick-state-fn path
    :transform-fn (fn []) �?{:translate [x y z] :rotate [x y z] :scale [x y z]} or nil
                   (optional)"
  [effect-id handler-map]
  (when-not (and (keyword? effect-id) (map? handler-map)
                 (or (fn? (:enqueue-fn handler-map))
                     (fn? (:enqueue-state-fn handler-map)))
                 (or (fn? (:tick-fn handler-map))
                     (fn? (:tick-state-fn handler-map))))
    (throw (IllegalArgumentException. "register-hand-effect!: invalid effect-id or handler-map")))
  (assert-registry-open!)
  (update-hand-effect-state!
    (fn [state]
      (if (get-in state [:registry effect-id])
        state
        (-> state
            (update :order conj effect-id)
            (assoc-in [:registry effect-id] handler-map)
            (assoc-effect-state effect-id (:initial-state handler-map))))))
  (log/debug "Registered hand effect:" effect-id)
  nil)

(defn freeze-hand-effect-registry!
  []
  (update-hand-effect-state! assoc :frozen? true)
  nil)

(defn hand-effect-registry-snapshot
  []
  (assoc (hand-effect-state-snapshot)
   :camera-pitch-deltas (camera-pitch-deltas-snapshot)
   ))

(defn effect-state-snapshot
  [effect-id]
  (get-in (hand-effect-state-snapshot) [:effect-states effect-id]))

(defn reset-hand-effect-state-for-test!
  [effect-id state]
  (update-hand-effect-state! assoc-effect-state effect-id state)
  nil)

(defn update-effect-state!
  [effect-id f & args]
  (update-hand-effect-state!
    (fn [state]
      (let [current-state (get-in state [:effect-states effect-id])
            next-state (apply f current-state args)]
        (assoc-effect-state state effect-id next-state))))
  nil)

(defn reset-hand-effect-registry-for-test!
  []
  (reset! (camera-pitch-deltas-atom) {})
  (reset! (hand-effect-state-atom) (default-hand-effect-runtime-state))
  nil)

;; ---------------------------------------------------------------------------
;; Dispatch
;; ---------------------------------------------------------------------------

(defn enqueue-hand-effect!
  "Dispatch an incoming FX payload to the registered hand-effect handler."
  [effect-id payload]
  (if-let [{:keys [enqueue-fn enqueue-state-fn]} (get-in (hand-effect-state-snapshot) [:registry effect-id])]
    (if enqueue-state-fn
      (update-hand-effect-state!
        (fn [state]
          (let [current-state (get-in state [:effect-states effect-id])
                next-state (enqueue-state-fn current-state payload)]
            (assoc-effect-state state effect-id next-state))))
      (enqueue-fn payload))
    (log/warn "No hand effect registered for" effect-id)))

(defn tick-hand-effects!
  "Tick all registered hand effects."
  []
  (let [{:keys [order registry]} (hand-effect-state-snapshot)]
    (doseq [eid order]
      (when-let [{:keys [tick-fn tick-state-fn]} (get registry eid)]
        (if tick-state-fn
          (update-hand-effect-state!
            (fn [state]
              (let [current-state (get-in state [:effect-states eid])
                    next-state (tick-state-fn current-state)]
                (assoc-effect-state state eid next-state))))
          (tick-fn))))))

(defn current-hand-transform
  "Merge transforms from all registered hand effects.
  Returns the first non-nil transform (priority = registration order)."
  []
  (let [{:keys [order registry]} (hand-effect-state-snapshot)]
    (some (fn [eid]
            (when-let [{:keys [transform-fn]} (get registry eid)]
              (when transform-fn
                (transform-fn))))
          order)))

;; ---------------------------------------------------------------------------
;; Introspection
;; ---------------------------------------------------------------------------

(defn registered-effects
  "Return the set of currently-registered hand effect ids."
  []
  (set (keys (:registry (hand-effect-state-snapshot)))))

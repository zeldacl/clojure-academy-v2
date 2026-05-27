(ns cn.li.ac.ability.client.hand-effects
  "Registry-based hand effect infrastructure for first-person hand animations.

  Skills register their hand-effect handlers via `register-hand-effect!` at
  load time.  The infrastructure dispatches enqueue / tick / transform calls
  without any skill-specific knowledge."
  (:require [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.util.log :as log]))

;; ---------------------------------------------------------------------------
;; Camera pitch delta accumulator (shared across all hand effects)
;; ---------------------------------------------------------------------------

(defonce ^:private camera-pitch-deltas (atom {}))

(defn- require-owner-value
  [owner label value]
  (if (some? value)
    value
    (throw (ex-info (format "Hand effects require %s" label)
                    {:owner owner
                     :required label}))))

(defn- current-session-id
  [owner]
  (require-owner-value owner
                       ":client-session-id"
                       (or (when (map? owner) (:client-session-id owner))
                           (when (map? owner) (:session-id owner))
                           (:client-session-id runtime-hooks/*player-state-owner*)
                           runtime-hooks/*client-session-id*)))

(defn- normalize-session-id
  [owner-or-session]
  (cond
    (map? owner-or-session)
    (current-session-id owner-or-session)

    (and (vector? owner-or-session)
         (= 2 (count owner-or-session))
         (vector? (first owner-or-session)))
    (first owner-or-session)

    (some? owner-or-session)
    owner-or-session

    :else
    (current-session-id nil)))

(defn current-effect-owner
  []
  (or runtime-hooks/*player-state-owner*
      (when runtime-hooks/*client-session-id*
        {:client-session-id runtime-hooks/*client-session-id*})
      (throw (ex-info "Current hand effect owner requires :client-session-id"
                      {:required ":client-session-id"}))))

(defn add-camera-pitch-delta!
  "Queue a camera pitch delta (float degrees) to be consumed by the platform
  renderer on the next frame."
  ([delta]
   (add-camera-pitch-delta! nil delta))
  ([owner-or-session delta]
   (swap! camera-pitch-deltas update (normalize-session-id owner-or-session) (fnil conj []) (float delta))
   nil))

(defn add-current-camera-pitch-delta!
  [delta]
  (add-camera-pitch-delta! (current-effect-owner) delta))

(defn drain-camera-pitch-deltas!
  "Atomically drain and return all queued camera pitch deltas."
  ([]
   (drain-camera-pitch-deltas! nil))
  ([owner-or-session]
   (let [session-id (normalize-session-id owner-or-session)
         drained (atom [])]
     (swap! camera-pitch-deltas
            (fn [deltas]
              (reset! drained (vec (get deltas session-id [])))
              (dissoc deltas session-id)))
     @drained)))

(defn clear-session-camera-pitch-deltas!
  ([]
   (clear-session-camera-pitch-deltas! nil))
  ([owner-or-session]
   (swap! camera-pitch-deltas dissoc (normalize-session-id owner-or-session))
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

;; effect-id → {:enqueue-fn    (fn [payload])
;;              :tick-fn        (fn [])
;;              :transform-fn   (fn []) → transform-map or nil  (optional)}
(defonce ^:private effect-registry (atom {}))
(defonce ^:private effect-order (atom []))
(defonce ^:private effect-registry-frozen? (atom false))

(defn- assert-registry-open!
  []
  (when @effect-registry-frozen?
    (throw (ex-info "Hand effect registry is frozen" {}))))

(defn register-hand-effect!
  "Register a hand effect handler.  `effect-id` is a keyword.

  `handler-map` keys:
    :enqueue-fn   (fn [payload]) — process incoming FX data
    :tick-fn      (fn []) — advance animation state each game tick
    :transform-fn (fn []) → {:translate [x y z] :rotate [x y z] :scale [x y z]} or nil
                   (optional)"
  [effect-id handler-map]
  {:pre [(keyword? effect-id) (map? handler-map)
         (fn? (:enqueue-fn handler-map))
         (fn? (:tick-fn handler-map))]}
  (assert-registry-open!)
  (when-not (get @effect-registry effect-id)
    (swap! effect-order conj effect-id)
    (swap! effect-registry assoc effect-id handler-map))
  (log/debug "Registered hand effect:" effect-id)
  nil)

(defn freeze-hand-effect-registry!
  []
  (reset! effect-registry-frozen? true)
  nil)

(defn hand-effect-registry-snapshot
  []
  {:registry @effect-registry
   :order @effect-order
   :camera-pitch-deltas @camera-pitch-deltas
   :frozen? @effect-registry-frozen?})

(defn reset-hand-effect-registry-for-test!
  []
  (reset! camera-pitch-deltas {})
  (reset! effect-registry {})
  (reset! effect-order [])
  (reset! effect-registry-frozen? false)
  nil)

;; ---------------------------------------------------------------------------
;; Dispatch
;; ---------------------------------------------------------------------------

(defn enqueue-hand-effect!
  "Dispatch an incoming FX payload to the registered hand-effect handler."
  [effect-id payload]
  (if-let [{:keys [enqueue-fn]} (get @effect-registry effect-id)]
    (enqueue-fn payload)
    (log/warn "No hand effect registered for" effect-id)))

(defn tick-hand-effects!
  "Tick all registered hand effects."
  []
  (doseq [eid @effect-order]
    (when-let [{:keys [tick-fn]} (get @effect-registry eid)]
      (tick-fn))))

(defn current-hand-transform
  "Merge transforms from all registered hand effects.
  Returns the first non-nil transform (priority = registration order)."
  []
  (some (fn [eid]
          (when-let [{:keys [transform-fn]} (get @effect-registry eid)]
            (when transform-fn
              (transform-fn))))
        @effect-order))

;; ---------------------------------------------------------------------------
;; Introspection
;; ---------------------------------------------------------------------------

(defn registered-effects
  "Return the set of currently-registered hand effect ids."
  []
  (set (keys @effect-registry)))

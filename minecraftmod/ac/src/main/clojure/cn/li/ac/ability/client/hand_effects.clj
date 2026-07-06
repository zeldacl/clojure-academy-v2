(ns cn.li.ac.ability.client.hand-effects
  "Registry-based hand effect infrastructure for first-person hand animations.

  Skills register their hand-effect handlers via `register-hand-effect!` at
  load time.  The infrastructure dispatches enqueue / tick / transform calls
  without any skill-specific knowledge."
  (:require [cn.li.ac.ability.client.effects.queue-infra :as queue-infra]
            [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.util.log :as log])
  (:import [java.util.concurrent ConcurrentLinkedQueue]))

;; ---------------------------------------------------------------------------
;; Camera pitch delta accumulator (shared across all hand effects)
;; ---------------------------------------------------------------------------

(def ^:private cp-path [:service :camera-pitch])

(defonce ^:private fallback-camera-pitch-queue (ConcurrentLinkedQueue.))

(defn- camera-pitch-deltas-queue ^ConcurrentLinkedQueue
  []
  (if-let [fw-atom (fw/fw-atom)]
    (or (get-in @fw-atom cp-path)
        (let [q (ConcurrentLinkedQueue.)]
          (swap! fw-atom assoc-in cp-path q)
          q))
    fallback-camera-pitch-queue))

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
   (queue-infra/queue-effect! (camera-pitch-deltas-queue) "hand" owner-or-session (float delta))
   nil))

(defn add-current-camera-pitch-delta!
  [delta]
  (add-camera-pitch-delta! (current-effect-owner) delta))

(defn drain-camera-pitch-deltas!
  "Atomically drain and return all queued camera pitch deltas."
  ([]
   (drain-camera-pitch-deltas! nil))
  ([owner-or-session]
   (queue-infra/poll-effects! (camera-pitch-deltas-queue) "hand" owner-or-session)))

(defn clear-session-camera-pitch-deltas!
  "Remove all queued camera pitch deltas for a specific session from the
  lock-free queue."
  ([]
   (clear-session-camera-pitch-deltas! nil))
  ([owner-or-session]
   (let [session-id (normalize-session-id owner-or-session)
         ^ConcurrentLinkedQueue q (camera-pitch-deltas-queue)]
     (.removeIf q (reify java.util.function.Predicate
                    (test [_ entry]
                      (= session-id (first entry))))))
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

(def ^:private he-path [:service :hand-effects])

(defonce ^:private fallback-hand-effect-state
  (atom (default-hand-effect-runtime-state)))

(defn- hand-effect-state-atom []
  (if-let [fw-atom (fw/fw-atom)]
    (or (get-in @fw-atom he-path)
        (let [a (atom (default-hand-effect-runtime-state))]
          (swap! fw-atom assoc-in he-path a) a))
    fallback-hand-effect-state))

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

(defn- assert-registry-open!
  []
  (when (:frozen? (hand-effect-state-snapshot))
    (throw (ex-info "Hand effect registry is frozen" {}))))

(defn register-hand-effect!
  "Register a hand effect handler.  `effect-id` is a keyword.

  `handler-map` keys:
    :enqueue-state-fn (fn [state ctx-id channel owner-key payload] -> state)
    :tick-state-fn    (fn [state] -> state)
    :initial-state    any (optional)
    :transform-fn     (fn [] -> transform-map or nil) (optional)"
  [effect-id handler-map]
  (when-not (and (keyword? effect-id) (map? handler-map)
                 (fn? (:enqueue-state-fn handler-map))
                 (fn? (:tick-state-fn handler-map)))
    (throw (IllegalArgumentException. "register-hand-effect!: invalid effect-id or handler-map")))
  (assert-registry-open!)
  (update-hand-effect-state!
    (fn [state]
      (if (get-in state [:registry effect-id])
        state
        (-> state
            (update :order conj effect-id)
            (assoc-in [:registry effect-id] handler-map)
            (assoc-effect-state effect-id
                               (let [init (:initial-state handler-map)]
                                 (if (fn? init) (init) init)))))))
  (log/debug "Registered hand effect:" effect-id)
  nil)

(defn freeze-hand-effect-registry!
  []
  (update-hand-effect-state! assoc :frozen? true)
  nil)

(defn hand-effect-registry-snapshot
  []
  (assoc (hand-effect-state-snapshot)
         :camera-pitch-deltas (vec (.toArray (camera-pitch-deltas-queue)))))

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
  (.clear (camera-pitch-deltas-queue))
  (reset! (hand-effect-state-atom) (default-hand-effect-runtime-state))
  nil)

;; ---------------------------------------------------------------------------
;; Dispatch
;; ---------------------------------------------------------------------------

(defn- default-owner-key [effect-id payload ctx-id _channel]
  (cond (and (map? payload) (:effect-instance-id payload)) [:effect-instance (:effect-instance-id payload)]
        ctx-id [:ctx ctx-id]
        (and (map? payload) (:source-player-id payload)) [:source-player (:source-player-id payload)]
        :else [:ctx ctx-id]))

(defn enqueue-hand-effect!
  "Dispatch an incoming FX payload to the registered hand-effect handler."
  [effect-id ctx-id channel payload & {:keys [owner-key]}]
  (if-let [{:keys [enqueue-state-fn]} (get-in (hand-effect-state-snapshot) [:registry effect-id])]
    (let [owner-key* (or owner-key (default-owner-key effect-id payload ctx-id channel))]
      (update-hand-effect-state!
        (fn [state]
          (let [current-state (get-in state [:effect-states effect-id])
                next-state (enqueue-state-fn current-state ctx-id channel owner-key* payload)]
            (assoc-effect-state state effect-id next-state))))))
    (log/warn "No hand effect registered for" effect-id))

(defn tick-hand-effects!
  "Tick all registered hand effects."
  []
  (let [{:keys [order registry]} (hand-effect-state-snapshot)]
    (doseq [eid order]
      (when-let [{:keys [tick-state-fn]} (get registry eid)]
        (update-hand-effect-state!
          (fn [state]
            (let [current-state (get-in state [:effect-states eid])
                  next-state (tick-state-fn current-state)]
              (assoc-effect-state state eid next-state))))))))

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

(defn registered-effects
  "Return the set of currently-registered hand effect ids."
  []
  (set (keys (:registry (hand-effect-state-snapshot)))))

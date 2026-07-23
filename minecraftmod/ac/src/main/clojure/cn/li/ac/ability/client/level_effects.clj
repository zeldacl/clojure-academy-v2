(ns cn.li.ac.ability.client.level-effects
  "Client-thread-confined level effect registry and state table."
  (:require [cn.li.mcmod.util.log :as log])
  (:import [java.util ArrayList HashMap]))

(defonce ^:private ^HashMap registry (HashMap.))
(defonce ^:private ^ArrayList effect-order (ArrayList.))
(defonce ^:private ^HashMap effect-states (HashMap.))
(defonce ^:private frozen (boolean-array 1))

(defn- assert-registry-open! []
  (when (aget ^booleans frozen 0)
    (throw (ex-info "Level effect registry is frozen" {}))))

(defn- default-empty-state?
  "True for the standard level-effect state shape: a map whose values are
  all empty collections (per-owner tables). Non-map / scalar-bearing states
  are never auto-emptied unless the effect supplies :empty-state?."
  [state]
  (and (map? state)
       (reduce-kv (fn [acc _ v]
                    (if (and (coll? v) (empty? v)) acc (reduced false)))
                  true state)))

(defn- put-effect-state! [effect-id state]
  (let [empty-state? (or (:empty-state? (.get registry effect-id)) default-empty-state?)]
    (if (or (nil? state) (empty-state? state))
      (.remove effect-states effect-id)
      (.put effect-states effect-id state)))
  nil)

(defn register-level-effect! [effect-id handler-map]
  (when-not (and (keyword? effect-id) (map? handler-map)
                 (fn? (:enqueue-state-fn handler-map))
                 (fn? (:tick-state-fn handler-map))
                 (or (nil? (:empty-state? handler-map)) (fn? (:empty-state? handler-map))))
    (throw (IllegalArgumentException.
             "register-level-effect!: invalid effect-id or handler-map")))
  (assert-registry-open!)
  (when-not (.containsKey registry effect-id)
    (.put registry effect-id handler-map)
    (.add effect-order effect-id)
    (let [init (:initial-state handler-map)]
      (put-effect-state! effect-id (if (fn? init) (init) init))))
  (log/debug "Registered level effect:" effect-id)
  nil)

(defn freeze-level-effect-registry! []
  (aset-boolean ^booleans frozen 0 true)
  nil)

(defn level-effect-registry-snapshot []
  {:registry (into {} registry)
   :order (vec effect-order)
   :effect-states (into {} effect-states)
   :frozen? (aget ^booleans frozen 0)})

(defn effect-state-snapshot [effect-id]
  (.get effect-states effect-id))

(defn reset-level-effect-state-for-test! [effect-id state]
  (put-effect-state! effect-id state))

(defn update-effect-state! [effect-id f & args]
  (put-effect-state! effect-id (apply f (.get effect-states effect-id) args)))

(defn reset-level-effect-registry-for-test! []
  (.clear registry)
  (.clear effect-order)
  (.clear effect-states)
  (aset-boolean ^booleans frozen 0 false)
  nil)

(defn- default-owner-key [effect-id payload ctx-id _channel]
  (cond
    (and (map? payload) (:effect-instance-id payload)) [:effect-instance (:effect-instance-id payload)]
    ctx-id [:ctx ctx-id]
    (and (map? payload) (:source-player-id payload)) [:source-player (:source-player-id payload)]
    (and (map? payload) (:player-id payload)) [:player (:player-id payload)]
    :else [:effect effect-id :global]))

(defn enqueue-level-effect!
  [effect-id ctx-id channel payload & {:keys [owner-key]}]
  (if-let [handler (.get registry effect-id)]
    (let [owner-key* (or owner-key (default-owner-key effect-id payload ctx-id channel))
          enqueue-state-fn (:enqueue-state-fn handler)]
      (put-effect-state!
        effect-id
        (enqueue-state-fn (.get effect-states effect-id)
                          ctx-id channel owner-key* payload)))
    (log/warn "No level effect registered for" effect-id)))

(defn tick-level-effects! []
  (dotimes [i (.size effect-order)]
    (let [eid (.get effect-order i)]
      (when-some [state (.get effect-states eid)]
        (when-let [tick-state-fn (:tick-state-fn (.get registry eid))]
          (put-effect-state! eid (tick-state-fn state))))))
  nil)

(defn any-level-effect-active? []
  (not (.isEmpty effect-states)))

(defn build-level-effect-plan
  "Build the combined render plan from all active level effects.

  build-plan-fn contract: fixed 4-arity [camera-pos hand-center-pos tick
  query-nearby-blocks-fn]. Effects with no state in effect-states (idle) are
  skipped without invoking build-plan-fn."
  [camera-pos hand-center-pos tick query-nearby-blocks-fn]
  (when-not (.isEmpty effect-states)
    (loop [i 0, ops (transient []), walk-speed nil]
      (if (< i (.size effect-order))
        (let [eid (.get effect-order i)
              state (.get effect-states eid)
              build-plan-fn (when state (:build-plan-fn (.get registry eid)))
              result (when build-plan-fn
                       (build-plan-fn camera-pos hand-center-pos tick query-nearby-blocks-fn))
              ops* (if-let [r (:ops result)] (reduce conj! ops r) ops)
              ws (:local-walk-speed result)
              walk-speed* (if (number? ws)
                            (if walk-speed (min (double walk-speed) (double ws)) (double ws))
                            walk-speed)]
          (recur (inc i) ops* walk-speed*))
        (let [ops-v (persistent! ops)]
          (when (or (seq ops-v) walk-speed)
            {:ops ops-v
             :local-walk-speed (when walk-speed (float walk-speed))}))))))

(defn registered-effects []
  (set (.keySet registry)))

(ns cn.li.mcmod.platform.world
  "Platform-agnostic world access abstraction layer."
  (:require [cn.li.mcmod.platform.runtime :as prt]))

(defprotocol IWorldAccess
  (world-get-tile-entity [this pos])
  (world-get-block-state [this pos])
  (world-set-block [this pos state flags])
  (world-remove-block [this pos])
  (world-break-block [this pos drop?])
  (world-place-block-by-id [this block-id pos flags])
  (world-is-chunk-loaded? [this chunk-x chunk-z])
  (world-get-day-time [this])
  (world-get-dimension-id [this])
  (world-server-session-id [this])
  (world-get-players [this])
  (world-is-raining [this])
  (world-is-client-side [this])
  (world-can-see-sky [this pos]))

(def ^:private ^:dynamic *world-ops* nil)
(def ^:private ^:const missing-op ::missing-op)

(defn install-world-ops!
  "Install world operation fns map. Keys match install-world-fns-only! in mc1201."
  [ops-map label]
  (prt/install-impl! #'*world-ops* ops-map (or label "world-ops")))

(defn world-ops-available? [] (prt/impl-available? #'*world-ops*))
(defn call-with-world-ops [ops f] (binding [*world-ops* ops] (f)))

(defn- world-op [k & args]
  (if-let [ops *world-ops*]
    (if-let [f (get ops k)]
      (apply f args)
      missing-op)
    missing-op))

(defn block-to-chunk-coord [block-coord]
  (bit-shift-right block-coord 4))

(defprotocol IBlockStateOps
  (block-state-is-air [this])
  (block-state-get-block [this])
  (block-state-get-state-definition [this])
  (block-state-get-property [this state-def prop-name])
  (block-state-set-property [this prop value]))

(defn world-is-client-side* [this]
  (let [r (world-op :world-is-client-side this)]
    (if (identical? r missing-op)
      (world-is-client-side this)
      (boolean r))))

(defn world-get-tile-entity* [this pos]
  (let [r (world-op :world-get-tile-entity this pos)]
    (if (identical? r missing-op)
      (world-get-tile-entity this pos)
      r)))

(defn world-get-block-state* [this pos]
  (let [r (world-op :world-get-block-state this pos)]
    (if (identical? r missing-op)
      (world-get-block-state this pos)
      r)))

(defn world-set-block* [this pos state flags]
  (let [r (world-op :world-set-block this pos state flags)]
    (boolean (if (identical? r missing-op)
               (world-set-block this pos state flags)
               r))))

(defn world-remove-block* [this pos]
  (let [r (world-op :world-remove-block this pos)]
    (boolean (if (identical? r missing-op)
               (world-remove-block this pos)
               r))))

(defn world-break-block* [this pos drop?]
  (let [r (world-op :world-break-block this pos drop?)]
    (boolean (if (identical? r missing-op)
               (world-break-block this pos drop?)
               r))))

(defn world-place-block-by-id* [this block-id pos flags]
  (let [r (world-op :world-place-block-by-id this block-id pos flags)]
    (boolean (if (identical? r missing-op)
               (world-place-block-by-id this block-id pos flags)
               r))))

(defn world-is-chunk-loaded?* [this chunk-x chunk-z]
  (let [r (world-op :world-is-chunk-loaded? this chunk-x chunk-z)]
    (boolean (if (identical? r missing-op)
               (world-is-chunk-loaded? this chunk-x chunk-z)
               r))))

(defn world-get-day-time* [this]
  (let [r (world-op :world-get-day-time this)]
    (long (if (identical? r missing-op)
            (world-get-day-time this)
            r))))

(defn world-get-dimension-id* [this]
  (let [r (world-op :world-get-dimension-id this)]
    (some-> (if (identical? r missing-op)
              (world-get-dimension-id this)
              r)
            str)))

(defn world-server-session-id* [this]
  (let [r (world-op :world-server-session-id this)]
    (if (identical? r missing-op)
      (world-server-session-id this)
      r)))

(defn world-get-players* [this]
  (let [r (world-op :world-get-players this)]
    (if (identical? r missing-op)
      (or (seq (world-get-players this))
          [])
      (or (seq r)
          []))))

(defn world-is-raining* [this]
  (let [r (world-op :world-is-raining this)]
    (boolean (if (identical? r missing-op)
               (world-is-raining this)
               r))))

(defn world-can-see-sky* [this pos]
  (let [r (world-op :world-can-see-sky this pos)]
    (boolean (if (identical? r missing-op)
               (world-can-see-sky this pos)
               r))))

(defn is-chunk-loaded-at-block?
  "Check if chunk containing block position is loaded."
  [world x z]
  (let [chunk-x (block-to-chunk-coord x)
        chunk-z (block-to-chunk-coord z)]
    (world-is-chunk-loaded?* world chunk-x chunk-z)))

(defn block-state-is-air? [block-state]
  (boolean (when block-state
             (block-state-is-air block-state))))

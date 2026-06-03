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
  (world-get-players [this])
  (world-is-raining [this])
  (world-is-client-side [this])
  (world-can-see-sky [this pos]))

(def ^:private ^:dynamic *world-ops* nil)

(defn install-world-ops!
  "Install world operation fns map. Keys match install-world-fns-only! in mc1201."
  [ops-map label]
  (prt/install-impl! #'*world-ops* ops-map (or label "world-ops")))

(defn world-ops-available? [] (prt/impl-available? #'*world-ops*))
(defn call-with-world-ops [ops f] (binding [*world-ops* ops] (f)))

(defn- world-op [k & args]
  (when-let [ops *world-ops*]
    (when-let [f (get ops k)]
      (apply f args))))

(defn block-to-chunk-coord [block-coord]
  (bit-shift-right block-coord 4))

(defprotocol IBlockStateOps
  (block-state-is-air [this])
  (block-state-get-block [this])
  (block-state-get-state-definition [this])
  (block-state-get-property [this state-def prop-name])
  (block-state-set-property [this prop value]))

(defn world-is-client-side* [this]
  (if-let [r (world-op :world-is-client-side this)]
    (boolean r)
    (world-is-client-side this)))

(defn world-get-tile-entity* [this pos]
  (or (world-op :world-get-tile-entity this pos)
      (world-get-tile-entity this pos)))

(defn world-get-block-state* [this pos]
  (or (world-op :world-get-block-state this pos)
      (world-get-block-state this pos)))

(defn world-set-block* [this pos state flags]
  (boolean (or (world-op :world-set-block this pos state flags)
               (world-set-block this pos state flags))))

(defn world-remove-block* [this pos]
  (boolean (or (world-op :world-remove-block this pos)
               (world-remove-block this pos))))

(defn world-break-block* [this pos drop?]
  (boolean (or (world-op :world-break-block this pos drop?)
               (world-break-block this pos drop?))))

(defn world-place-block-by-id* [this block-id pos flags]
  (boolean (or (world-op :world-place-block-by-id this block-id pos flags)
               (world-place-block-by-id this block-id pos flags))))

(defn world-is-chunk-loaded?* [this chunk-x chunk-z]
  (boolean (or (world-op :world-is-chunk-loaded? this chunk-x chunk-z)
               (world-is-chunk-loaded? this chunk-x chunk-z))))

(defn world-get-day-time* [this]
  (long (or (world-op :world-get-day-time this)
            (world-get-day-time this))))

(defn world-get-dimension-id* [this]
  (some-> (or (world-op :world-get-dimension-id this)
              (world-get-dimension-id this))
          str))

(defn world-get-players* [this]
  (or (seq (world-op :world-get-players this))
      (seq (world-get-players this))
      []))

(defn world-is-raining* [this]
  (boolean (or (world-op :world-is-raining this)
               (world-is-raining this))))

(defn world-can-see-sky* [this pos]
  (boolean (or (world-op :world-can-see-sky this pos)
               (world-can-see-sky this pos))))

(defn is-chunk-loaded-at-block?
  "Check if chunk containing block position is loaded."
  [world x z]
  (let [chunk-x (block-to-chunk-coord x)
        chunk-z (block-to-chunk-coord z)]
    (world-is-chunk-loaded?* world chunk-x chunk-z)))

(defn block-state-is-air? [block-state]
  (boolean (when block-state
             (block-state-is-air block-state))))

(ns cn.li.mcmod.block.tile-kind
  "Declarative tile-kind defaults merged at bundle compile time (registration phase only)."
  (:require [cn.li.mcmod.protocol.core :as registry-core]
            [cn.li.mcmod.util.log :as log]))

(def ^:private ^:dynamic *tile-kind-registry-state* {})

(defonce tile-kind-registry
  (registry-core/var-root-registry #'*tile-kind-registry-state*))

(defn register-tile-kind!
  "Register reusable tile logic defaults by tile-kind keyword."
  [tile-kind cfg]
  (when-not (keyword? tile-kind)
    (throw (ex-info "register-tile-kind!: tile-kind must be keyword"
                    {:tile-kind tile-kind})))
  (registry-core/swap-state! tile-kind-registry #(assoc % tile-kind cfg))
  (log/info "Registered tile-kind logic" tile-kind)
  nil)

(defn merge-with-kind
  "Merge kind-cfg with normalized. Nil values in normalized do not override kind defaults."
  [kind-cfg normalized]
  {:pre [(map? kind-cfg)
         (or (map? normalized) (nil? normalized))]}
  (cond
    (empty? kind-cfg) normalized
    (nil? normalized) kind-cfg
    :else (reduce-kv (fn [m k v]
                       (if (nil? v) m (assoc m k v)))
                     kind-cfg
                     normalized)))

(defn merge-tile-kind-defaults
  "Merge :tile-kind defaults into a tile spec map for bundle compilation."
  [spec]
  (let [kind-cfg (when-let [k (:tile-kind spec)]
                   (registry-core/lookup tile-kind-registry k))
        hook-keys [:tick-fn :read-nbt-fn :write-nbt-fn :container :capability-keys]
        normalized (select-keys spec hook-keys)]
    (merge-with-kind (or kind-cfg {}) normalized)))

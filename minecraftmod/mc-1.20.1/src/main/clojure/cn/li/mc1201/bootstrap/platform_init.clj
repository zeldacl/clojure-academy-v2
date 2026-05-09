(ns cn.li.mc1201.bootstrap.platform-init
  "Shared platform bootstrap install wrappers.

  Centralizes installer call sequences so platform SPI bootstraps keep only
  adapter creation and platform-specific hook maps/extensions."
  (:require [cn.li.mc1201.installer :as installer]
            [cn.li.mc1201.platform-adapter :as pa]))

(defn install-platform-core!
  "Install the full shared platform core for adapters that can provide
  all required world/block/entity/item operations through PlatformAdapter."
  [adapter]
  (installer/install-platform-core! adapter))

(defn install-platform-foundation+hooks!
  "Install shared foundation + adapter-driven protocols and factories,
  then apply platform-provided world/be var-root hooks.

  This is used by platforms that still keep world/be hooks in platform bindings
  during incremental migration."
  [adapter world-fns-map be-fns-map]
  (installer/install-foundation!)
  (installer/install-entity-protocols-only! adapter)
  (installer/install-item-protocols-only! adapter)
  (installer/install-block-state-protocol-only! adapter)
  (installer/install-resource-factory-only!)
  (installer/install-item-factories-only!
    (fn [nbt] (pa/item-stack-of adapter nbt))
    (fn [item-id count] (pa/create-item-stack-by-id adapter item-id count))
    (fn [stack] (pa/item-stack-empty? adapter stack)))
  (when world-fns-map
    (installer/install-world-fns-only! world-fns-map))
  (when be-fns-map
    (installer/install-be-fns-only! be-fns-map)))

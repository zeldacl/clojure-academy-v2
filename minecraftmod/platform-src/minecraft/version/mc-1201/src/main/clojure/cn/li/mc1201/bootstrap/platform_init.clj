(ns cn.li.mc1201.bootstrap.platform-init
  "Shared platform bootstrap install wrappers.

  Centralizes installer call sequences so platform SPI bootstraps keep only
  adapter creation and platform-specific hook maps/extensions."
  (:require [cn.li.mc1201.bootstrap.installer-core :as core]
            [cn.li.mc1201.runtime.accessor-registry :as accessor-registry]))

(defn install-platform-core!
  "Install the full shared platform core for adapters that can provide
  all required world/block/entity/item operations through PlatformAdapter."
  [adapter]
  (core/install-platform-core! adapter)
  (accessor-registry/init-default-accessors!))

(defn install-platform-foundation+hooks!
  "Install shared foundation + adapter-driven protocols and factories,
  then apply platform-provided world/be var-root hooks.

  This is used by platforms that still keep world/be hooks in platform bindings
  during incremental migration."
  [adapter world-fns-map be-fns-map]
  (core/install-foundation!)
  (core/install-entity-protocols-only! adapter)
  (core/install-item-protocols-only! adapter)
  (core/install-block-state-protocol-only! adapter)
  (core/install-resource-factory-only!)
  (when world-fns-map
    (core/install-world-fns-only! world-fns-map))
  (when be-fns-map
    (core/install-be-fns-only! be-fns-map))
  (accessor-registry/init-default-accessors!))

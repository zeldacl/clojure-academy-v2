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

(defn install-platform-services!
  [adapter world-fns-map be-fns-map]
  (core/install-platform-services! adapter world-fns-map be-fns-map)
  (accessor-registry/init-default-accessors!))

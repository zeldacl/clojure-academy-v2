(ns cn.li.fabric1201.runtime.install
  "Fabric runtime adapter installer.

  Keeps mod entry focused by grouping runtime protocol installation in one place."
  (:require [cn.li.fabric1201.runtime.damage-interception :as runtime-damage-interception]
            [cn.li.fabric1201.runtime.item-handler :as runtime-item-handler]
            [cn.li.fabric1201.runtime.player-motion :as runtime-player-motion]
            [cn.li.fabric1201.runtime.entity-damage :as runtime-entity-damage]
            [cn.li.fabric1201.runtime.entity-motion :as runtime-entity-motion]
            [cn.li.fabric1201.runtime.entity-query :as runtime-entity-query]
            [cn.li.fabric1201.runtime.raycast :as runtime-raycast]
            [cn.li.fabric1201.runtime.world-effects :as runtime-world-effects]
            [cn.li.fabric1201.runtime.teleportation :as runtime-teleportation]
            [cn.li.fabric1201.runtime.saved-locations :as runtime-saved-locations]
            [cn.li.fabric1201.runtime.potion-effects :as runtime-potion-effects]
            [cn.li.fabric1201.runtime.interop :as runtime-interop]
            [cn.li.fabric1201.runtime.block-manipulation :as runtime-block-manipulation]
            [cn.li.fabric1201.runtime.network :as runtime-network]
            [cn.li.mcmod.platform.power-runtime :as power-runtime]))

(defn install-runtime-adapters!
  []
  (runtime-damage-interception/install-damage-interception!)
  (runtime-item-handler/init!)
  (runtime-player-motion/install-player-motion!)
  (runtime-entity-damage/install-entity-damage!)
  (runtime-entity-motion/install-entity-motion!)
  (runtime-entity-query/install-entity-query!)
  (runtime-raycast/install-raycast!)
  (runtime-world-effects/install-world-effects!)
  (runtime-teleportation/install-teleportation!)
  (runtime-saved-locations/install-saved-locations!)
  (runtime-potion-effects/install-potion-effects!)
  (runtime-interop/install-runtime-interop!)
  (runtime-block-manipulation/install-block-manipulation!)
  (runtime-network/init!)
  (power-runtime/init-damage-handlers!)
  nil)

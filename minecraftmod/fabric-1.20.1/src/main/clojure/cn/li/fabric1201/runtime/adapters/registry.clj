(ns cn.li.fabric1201.runtime.adapters.registry
  "Declarative runtime adapter install registry for Fabric."
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
            [cn.li.fabric1201.adapter.network :as runtime-network]
            [cn.li.mc1201.runtime.adapter-registry :as adapter-registry]
            [cn.li.mcmod.hooks.core :as power-runtime]))

(def runtime-install-steps
  [(adapter-registry/step :damage-interception runtime-damage-interception/install-damage-interception!)
   (adapter-registry/step :item-handler runtime-item-handler/init!)
   (adapter-registry/step :player-motion runtime-player-motion/install-player-motion!)
   (adapter-registry/step :entity-damage runtime-entity-damage/install-entity-damage!)
   (adapter-registry/step :entity-motion runtime-entity-motion/install-entity-motion!)
   (adapter-registry/step :entity-query runtime-entity-query/install-entity-query!)
   (adapter-registry/step :raycast runtime-raycast/install-raycast!)
   (adapter-registry/step :world-effects runtime-world-effects/install-world-effects!)
   (adapter-registry/step :teleportation runtime-teleportation/install-teleportation!)
   (adapter-registry/step :saved-locations runtime-saved-locations/install-saved-locations!)
   (adapter-registry/step :potion-effects runtime-potion-effects/install-potion-effects!)
   (adapter-registry/step :runtime-interop runtime-interop/install-runtime-interop!)
   (adapter-registry/step :block-manipulation runtime-block-manipulation/install-block-manipulation!)
   (adapter-registry/step :network runtime-network/init!)
   (adapter-registry/step :damage-handlers power-runtime/init-damage-handlers!)])

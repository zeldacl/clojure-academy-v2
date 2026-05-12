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
            [cn.li.fabric1201.runtime.network :as runtime-network]
            [cn.li.mcmod.runtime.hooks-core :as power-runtime]))

(def runtime-install-steps
  [{:id :damage-interception :install-fn runtime-damage-interception/install-damage-interception!}
   {:id :item-handler :install-fn runtime-item-handler/init!}
   {:id :player-motion :install-fn runtime-player-motion/install-player-motion!}
   {:id :entity-damage :install-fn runtime-entity-damage/install-entity-damage!}
   {:id :entity-motion :install-fn runtime-entity-motion/install-entity-motion!}
   {:id :entity-query :install-fn runtime-entity-query/install-entity-query!}
   {:id :raycast :install-fn runtime-raycast/install-raycast!}
   {:id :world-effects :install-fn runtime-world-effects/install-world-effects!}
   {:id :teleportation :install-fn runtime-teleportation/install-teleportation!}
   {:id :saved-locations :install-fn runtime-saved-locations/install-saved-locations!}
   {:id :potion-effects :install-fn runtime-potion-effects/install-potion-effects!}
   {:id :runtime-interop :install-fn runtime-interop/install-runtime-interop!}
   {:id :block-manipulation :install-fn runtime-block-manipulation/install-block-manipulation!}
   {:id :network :install-fn runtime-network/init!}
   {:id :damage-handlers :install-fn power-runtime/init-damage-handlers!}])

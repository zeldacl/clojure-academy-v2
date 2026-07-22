(ns cn.li.fabric1201.runtime.adapters.registry
  "Declarative runtime adapter install registry for Fabric."
  (:require [cn.li.fabric1201.runtime.damage-interception :as runtime-damage-interception]
            [cn.li.fabric1201.runtime.item-handler :as runtime-item-handler]
            [cn.li.fabric1201.runtime.player-motion :as runtime-player-motion]
            [cn.li.fabric1201.runtime.entity-damage :as runtime-entity-damage]
            [cn.li.fabric1201.runtime.entity-motion :as runtime-entity-motion]
            [cn.li.fabric1201.runtime.entity-query :as runtime-entity-query]
            [cn.li.fabric1201.runtime.world-effects :as runtime-world-effects]
            [cn.li.fabric1201.runtime.block-manipulation :as runtime-block-manipulation]
            [cn.li.fabric1201.adapter.network :as runtime-network]
            [cn.li.fabric1201.adapter.server-context :as server-context]
            [cn.li.mc1201.runtime.adapter-registry :as adapter-registry]
            [cn.li.mc1201.runtime.interop-core :as interop-core]
            [cn.li.mc1201.runtime.raycast-core :as raycast-core]
            [cn.li.mc1201.runtime.teleportation-core :as teleportation-core]
            [cn.li.mc1201.runtime.named-position-store-core :as named-position-store-core]
            [cn.li.mc1201.runtime.potion-effects-core :as potion-effects-core]
            [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.framework.platform :as platform]
            [cn.li.mcmod.platform.raycast :as prc]
            [cn.li.mcmod.platform.teleportation :as ptp]
            [cn.li.mcmod.hooks.core :as power-runtime]))

(defn- install-bound-adapter!
  [install-fn create-adapter label]
  (install-fn (create-adapter server-context/get-server) label))

(def runtime-install-steps
  [(adapter-registry/step :damage-interception runtime-damage-interception/install-damage-interception!)
   (adapter-registry/step :item-handler runtime-item-handler/init!)
   (adapter-registry/step :player-motion
                          #(when-let [fw-atom (fw/fw-atom)]
                             (platform/install-adapter!
                               fw-atom
                               :player-motion
                               (runtime-player-motion/fabric-player-motion))))
   (adapter-registry/step :entity-damage runtime-entity-damage/install-entity-damage!)
   (adapter-registry/step :entity-motion
                          #(when-let [fw-atom (fw/fw-atom)]
                             (platform/install-adapter!
                               fw-atom
                               :entity-motion
                               (runtime-entity-motion/fabric-entity-motion))))
   (adapter-registry/step :entity-query runtime-entity-query/install-entity-query!)
   (adapter-registry/step :raycast
                          #(install-bound-adapter! prc/install-raycast!
                                                   raycast-core/create-raycast
                                                   "Fabric raycast"))
   (adapter-registry/step :world-effects runtime-world-effects/install-world-effects!)
   (adapter-registry/step :teleportation
                          #(install-bound-adapter! ptp/install-teleportation!
                                                   teleportation-core/create-teleportation
                                                   "Fabric teleportation"))
   (adapter-registry/step :named-position-store
                          #(when-let [fw-atom (fw/fw-atom)]
                             (platform/install-adapter!
                               fw-atom
                               :named-position-store
                               (named-position-store-core/create-named-position-store server-context/get-server))))
   (adapter-registry/step :potion-effects
                          #(when-let [fw-atom (fw/fw-atom)]
                             (platform/install-adapter!
                               fw-atom
                               :potion-effects
                               (potion-effects-core/create-potion-effects server-context/get-server))))
   (adapter-registry/step :runtime-interop
                          #(interop-core/install-runtime-interop! "Fabric" server-context/get-server))
   (adapter-registry/step :block-manipulation runtime-block-manipulation/install-block-manipulation!)
   (adapter-registry/step :network runtime-network/init!)
   (adapter-registry/step :damage-handlers power-runtime/init-damage-handlers!)])

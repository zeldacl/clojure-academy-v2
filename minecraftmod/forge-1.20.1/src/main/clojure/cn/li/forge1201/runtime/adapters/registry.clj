(ns cn.li.forge1201.runtime.adapters.registry
  "Declarative Forge runtime adapter registry."
  (:require [cn.li.forge1201.runtime.entity-damage :as entity-damage]
            [cn.li.forge1201.runtime.world-effects :as world-effects]
            [cn.li.forge1201.runtime.block-manipulation :as block-manipulation]
            [cn.li.forge1201.runtime.damage-interception :as damage-interception]
            [cn.li.forge1201.adapter.server-context :as server-context]
            [cn.li.mc1201.runtime.adapter-registry :as adapter-registry]
            [cn.li.mc1201.runtime.interop-core :as interop-core]
            [cn.li.mc1201.runtime.teleportation-core :as teleportation-core]
            [cn.li.mc1201.runtime.named-position-store-core :as position-store-core]
            [cn.li.mc1201.runtime.potion-effects-core :as potion-effects-core]
            [cn.li.mc1201.runtime.player-motion-core :as player-motion-core]
            [cn.li.mc1201.runtime.entity-motion-core :as entity-motion-core]
            [cn.li.mc1201.runtime.raycast-core :as raycast-core]
            [cn.li.mc1201.runtime.entity-query-core :as entity-query-core]
            [cn.li.mcmod.platform.teleportation :as ptp]
            [cn.li.mcmod.platform.named-position-store :as position-store]
            [cn.li.mcmod.platform.potion-effects :as ppe]
            [cn.li.mcmod.platform.player-motion :as pm]
            [cn.li.mcmod.platform.entity-motion :as pem]
            [cn.li.mcmod.platform.raycast :as prc]
            [cn.li.mcmod.platform.entity :as pentity]))

(defn- install-bound-adapter!
  [install-fn create-adapter label]
  (install-fn (create-adapter server-context/get-server) label))

(def runtime-install-steps
  [(adapter-registry/step :entity-damage
                          entity-damage/install-entity-damage!)
   (adapter-registry/step :raycast
                          #(install-bound-adapter! prc/install-raycast!
                                                   raycast-core/create-raycast
                                                   "Forge raycast"))
   (adapter-registry/step :interop
                          #(interop-core/install-runtime-interop! "Forge" server-context/get-server))
   (adapter-registry/step :world-effects
                          world-effects/install-world-effects!)
   (adapter-registry/step :potion-effects
                          #(install-bound-adapter! ppe/install-potion-effects!
                                                   potion-effects-core/create-potion-effects
                                                   "Forge potion effects"))
   (adapter-registry/step :teleportation
                          #(install-bound-adapter! ptp/install-teleportation!
                                                   teleportation-core/create-teleportation
                                                   "Forge teleportation"))
   (adapter-registry/step :named-position-store
                          #(install-bound-adapter! position-store/install-named-position-store!
                                                   position-store-core/create-named-position-store
                                                   "Forge named position store"))
   (adapter-registry/step :player-motion
                          #(install-bound-adapter! pm/install-player-motion!
                                                   player-motion-core/create-player-motion
                                                   "Forge player motion"))
   (adapter-registry/step :entity-motion
                          #(install-bound-adapter! pem/install-entity-motion!
                                                   entity-motion-core/create-entity-motion
                                                   "Forge entity motion"))
   (adapter-registry/step :entity-query
                          #(pentity/install-entity-type-id-fn!
                             (entity-query-core/create-entity-type-id-fn server-context/get-server)
                             "Forge entity query"))
   (adapter-registry/step :block-manipulation
                          block-manipulation/install-block-manipulation!)
   (adapter-registry/step :damage-interception
                          damage-interception/install-damage-interception!)])

(def adapter-installers runtime-install-steps)

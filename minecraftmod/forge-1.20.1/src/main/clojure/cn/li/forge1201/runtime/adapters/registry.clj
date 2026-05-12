(ns cn.li.forge1201.runtime.adapters.registry
  "Declarative Forge runtime adapter registry.

  Centralizes adapter installation wiring so micro one-function namespaces are
  not needed."
  (:require [cn.li.forge1201.runtime.entity-damage :as entity-damage]
            [cn.li.forge1201.runtime.world-effects :as world-effects]
            [cn.li.forge1201.runtime.block-manipulation :as block-manipulation]
            [cn.li.forge1201.runtime.damage-interception :as damage-interception]
            [cn.li.forge1201.runtime.server-context :as server-context]
            [cn.li.mc1201.runtime.adapter-support :as adapter-support]
            [cn.li.mc1201.runtime.interop-core :as interop-core]
            [cn.li.mc1201.runtime.teleportation-core :as teleportation-core]
            [cn.li.mc1201.runtime.saved-locations-core :as saved-locations-core]
            [cn.li.mc1201.runtime.potion-effects-core :as potion-effects-core]
            [cn.li.mc1201.runtime.player-motion-core :as player-motion-core]
            [cn.li.mc1201.runtime.entity-motion-core :as entity-motion-core]
            [cn.li.mc1201.runtime.raycast-core :as raycast-core]
            [cn.li.mc1201.runtime.entity-query-core :as entity-query-core]
            [cn.li.mcmod.platform.teleportation :as ptp]
            [cn.li.mcmod.platform.saved-locations :as psl]
            [cn.li.mcmod.platform.potion-effects :as ppe]
            [cn.li.mcmod.platform.player-motion :as pm]
            [cn.li.mcmod.platform.entity-motion :as pem]
            [cn.li.mcmod.platform.raycast :as prc]
            [cn.li.mcmod.platform.entity :as pentity]))

(defn- install-bound-adapter!
  [adapter-var create-adapter label]
  (adapter-support/install-adapter! adapter-var
                                    (create-adapter server-context/get-server)
                                    label))

(def adapter-installers
  [{:id :entity-damage
    :install entity-damage/install-entity-damage!}
   {:id :raycast
    :install #(install-bound-adapter! #'prc/*raycast*
                                      raycast-core/create-raycast
                                      "Forge raycast")}
   {:id :interop
    :install #(interop-core/install-runtime-interop! "Forge" server-context/get-server)}
   {:id :world-effects
    :install world-effects/install-world-effects!}
   {:id :potion-effects
    :install #(install-bound-adapter! #'ppe/*potion-effects*
                                      potion-effects-core/create-potion-effects
                                      "Forge potion effects")}
   {:id :teleportation
    :install #(install-bound-adapter! #'ptp/*teleportation*
                                      teleportation-core/create-teleportation
                                      "Forge teleportation")}
   {:id :saved-locations
    :install #(install-bound-adapter! #'psl/*saved-locations*
                                      saved-locations-core/create-saved-locations
                                      "Forge saved locations")}
   {:id :player-motion
    :install #(install-bound-adapter! #'pm/*player-motion*
                                      player-motion-core/create-player-motion
                                      "Forge player motion")}
   {:id :entity-motion
    :install #(install-bound-adapter! #'pem/*entity-motion*
                                      entity-motion-core/create-entity-motion
                                      "Forge entity motion")}
   {:id :entity-query
    :install #(adapter-support/install-adapter! #'pentity/*entity-get-type-id-fn*
                                                (entity-query-core/create-entity-type-id-fn server-context/get-server)
                                                "Forge entity query")}
   {:id :block-manipulation
    :install block-manipulation/install-block-manipulation!}
   {:id :damage-interception
    :install damage-interception/install-damage-interception!}])

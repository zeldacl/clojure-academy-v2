(ns cn.li.forge1201.runtime.entity-damage
  "Forge implementation of IEntityDamage protocol."
  (:require [cn.li.mc1201.runtime.entity-damage-adapter :as adapter]
            [cn.li.mc1201.runtime.adapter-support :as adapter-support]
            [cn.li.mc1201.runtime.entity-query-core :as query-core]
            [cn.li.mcmod.platform.entity-damage :as ped]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.server MinecraftServer]
           [cn.li.forge1201.bridge ForgeRuntimeBridge]
           [net.minecraft.server.level ServerLevel]
           [net.minecraft.world.entity Entity LivingEntity]
           [net.minecraft.world.phys AABB]
           [net.minecraftforge.server ServerLifecycleHooks]))

(defn- get-server ^MinecraftServer []
  (ServerLifecycleHooks/getCurrentServer))

(defn forge-entity-damage []
  (adapter/create-entity-damage
    get-server
    {:resolve-level-fn (fn [server world-id] (query-core/resolve-level server world-id))
     :get-entity-by-uuid-fn query-core/get-entity-by-uuid
     :get-living-entities-in-aabb-fn (fn [^ServerLevel level ^AABB aabb]
                                       (ForgeRuntimeBridge/getLivingEntitiesInAabb level aabb))
     :living-entity?-fn (fn [entity] (ForgeRuntimeBridge/isLivingEntity ^Entity entity))
     :apply-hurt-fn (fn [^LivingEntity entity dmg-source damage]
                      (.hurt entity dmg-source (float damage)))}))

(defn install-entity-damage! []
  (adapter-support/install-adapter! #'ped/*entity-damage*
                                    (forge-entity-damage)
                                    "Forge entity damage"))


(ns cn.li.forge1201.runtime.world-effects
  "Forge implementation of IWorldEffects protocol."
  (:require [cn.li.mc1201.runtime.world-effects-adapter :as adapter]
            [cn.li.mc1201.runtime.adapter-support :as adapter-support]
            [cn.li.mc1201.runtime.entity-query-core :as query-core]
            [cn.li.mcmod.platform.world-effects :as pwe]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.server MinecraftServer]
           [cn.li.forge1201.bridge ForgeRuntimeBridge]
           [net.minecraft.server.level ServerLevel]
           [net.minecraft.world.level.block Block]
           [net.minecraft.world.phys AABB]
           [net.minecraftforge.server ServerLifecycleHooks]))

(defn- get-server ^MinecraftServer []
  (ServerLifecycleHooks/getCurrentServer))

(defn forge-world-effects []
  (adapter/create-world-effects
    get-server
    {:resolve-level-fn (fn [server world-id] (query-core/resolve-level-strict server world-id))
     :spawn-lightning-fn (fn [^ServerLevel level x y z]
                           (ForgeRuntimeBridge/spawnLightning level (double x) (double y) (double z)))
     :create-explosion-fn (fn [^ServerLevel level x y z radius fire?]
                            (ForgeRuntimeBridge/createExplosion level x y z (float radius) (boolean fire?))
                            true)
     :get-entities-in-aabb-fn (fn [^ServerLevel l ^AABB aabb]
                                (ForgeRuntimeBridge/getEntitiesInAabb l aabb))
     :resolve-entity-id-fn (fn [entity]
                             (ForgeRuntimeBridge/getEntityRegistryId entity))
     :block-id-fn (fn [^Block block _block-state]
                    (str (.getDescriptionId block)))}))

(defn install-world-effects! []
  (adapter-support/install-adapter! #'pwe/*world-effects*
                                    (forge-world-effects)
                                    "Forge world effects"))

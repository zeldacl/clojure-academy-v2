(ns cn.li.forge1201.runtime.world-effects
  "Forge implementation of IWorldEffects protocol."
  (:require [cn.li.mc1201.runtime.adapter.world-effects :as world-effects]
            [cn.li.mc1201.runtime.entity-query-core :as query-core]
            [cn.li.forge1201.adapter.server-context :as server-context])
  (:import [cn.li.mc1201.runtime RuntimeAccessShared WorldEntityShared]
           [net.minecraft.server.level ServerLevel]
           [net.minecraft.world.level.block Block]
           [net.minecraft.world.phys AABB]))

(defn forge-world-effects []
  (world-effects/create-world-effects
    server-context/get-server
    {:resolve-level-fn (fn [server world-id] (query-core/resolve-level-strict server world-id))
     :spawn-lightning-fn (fn [^ServerLevel level x y z visual-only?]
                 (WorldEntityShared/spawnLightning level (double x) (double y) (double z) (boolean visual-only?)))
     :create-explosion-fn (fn [^ServerLevel level x y z radius fire?]
                (WorldEntityShared/createExplosion level x y z (float radius) (boolean fire?))
                            true)
     :get-entities-in-aabb-fn (fn [^ServerLevel l ^AABB aabb]
                  (WorldEntityShared/getEntitiesInAabb l aabb))
     :resolve-entity-id-fn (fn [entity]
                 (RuntimeAccessShared/getEntityRegistryId entity))
     :block-id-fn (fn [^Block block _block-state]
                    (str (.getDescriptionId block)))}))

(defn install-world-effects! []
  (world-effects/install-world-effects! (forge-world-effects)
                                        "Forge world effects"))

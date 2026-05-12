(ns cn.li.forge1201.runtime.world-effects
  "Forge implementation of IWorldEffects protocol."
  (:require [cn.li.mc1201.runtime.world-effects-core :as core]
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

(defn- get-level ^ServerLevel [world-id]
  (when-let [^MinecraftServer server (get-server)]
    (query-core/resolve-level-strict server world-id)))

(defn- spawn-lightning-impl! [world-id x y z]
  (try
    (when-let [^ServerLevel level (get-level world-id)]
      (ForgeRuntimeBridge/spawnLightning level (double x) (double y) (double z)))
    (catch Exception e
      (log/warn "Failed to spawn lightning:" (ex-message e))
      false)))

(defn- create-explosion-impl! [world-id x y z radius fire?]
  (try
    (when-let [^ServerLevel level (get-level world-id)]
      (ForgeRuntimeBridge/createExplosion level x y z (float radius) (boolean fire?))
      true)
    (catch Exception e
      (log/warn "Failed to create explosion:" (ex-message e))
      false)))

(defn- find-entities-in-radius-impl [world-id x y z radius]
  (try
    (when-let [^ServerLevel level (get-level world-id)]
      (core/entities-in-radius
        level
        x y z radius
        (fn [^ServerLevel l ^AABB aabb]
          (ForgeRuntimeBridge/getEntitiesInAabb l aabb))
        (fn [entity]
          (ForgeRuntimeBridge/getEntityRegistryId entity))))
    (catch Exception e
      (log/warn "Failed to find entities:" (ex-message e))
      [])))
(defn- find-blocks-in-radius-impl [world-id x y z radius block-predicate]
  (try
    (when-let [^ServerLevel level (get-level world-id)]
      (core/find-blocks-in-radius-in-level
        level
        x y z radius block-predicate
        (fn [^Block block _block-state]
          (str (.getDescriptionId block)))))
    (catch Exception e
      (log/warn "Failed to find blocks:" (ex-message e))
      [])))

(defn- play-sound-impl! [world-id x y z sound-id source volume pitch]
  (try
    (when-let [^ServerLevel level (get-level world-id)]
      (boolean (core/play-sound-in-level! level x y z sound-id source volume pitch)))
    (catch Exception e
      (log/warn "Failed to play world sound:" (ex-message e))
      false)))

(defn forge-world-effects []
  (reify pwe/IWorldEffects
    (spawn-lightning! [_ world-id x y z]
      (spawn-lightning-impl! world-id x y z))
    (create-explosion! [_ world-id x y z radius fire?]
      (create-explosion-impl! world-id x y z radius fire?))
    (find-entities-in-radius [_ world-id x y z radius]
      (find-entities-in-radius-impl world-id x y z radius))
    (find-blocks-in-radius [_ world-id x y z radius block-predicate]
      (find-blocks-in-radius-impl world-id x y z radius block-predicate))
    (play-sound! [_ world-id x y z sound-id source volume pitch]
      (boolean (play-sound-impl! world-id x y z sound-id source volume pitch)))))

(defn install-world-effects! []
  (alter-var-root #'pwe/*world-effects*
                  (constantly (forge-world-effects)))
  (log/info "Forge world effects installed"))

(ns cn.li.fabric1201.runtime.world-effects
  "Fabric implementation of IWorldEffects protocol."
  (:require [cn.li.fabric1201.runtime.server-context :as server-context]
            [cn.li.mc1201.runtime.world-effects-core :as core]
            [cn.li.mc1201.runtime.entity-query-core :as query-core]
            [cn.li.mcmod.platform.world-effects :as pwe]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.server MinecraftServer]
           [net.minecraft.server.level ServerLevel]
           [net.minecraft.world.level Level$ExplosionInteraction]
           [net.minecraft.world.entity EntityType LightningBolt]
           [net.minecraft.world.phys AABB]))

(defonce ^:private installed? (atom false))

(defn- get-server ^MinecraftServer []
  (server-context/get-server))

(defn- get-level ^ServerLevel [world-id]
  (when-let [^MinecraftServer server (get-server)]
    (query-core/resolve-level-strict server world-id)))

(defn- spawn-lightning-impl! [world-id x y z]
  (try
    (when-let [^ServerLevel level (get-level world-id)]
      (let [bolt (.create EntityType/LIGHTNING_BOLT level)]
        (when bolt
          (.moveTo ^LightningBolt bolt (double x) (double y) (double z))
          (boolean (.addFreshEntity level bolt)))))
    (catch Exception e
      (log/warn "[fabric] Failed to spawn lightning:" (ex-message e))
      false)))

(defn- create-explosion-impl! [world-id x y z radius fire?]
  (try
    (when-let [^ServerLevel level (get-level world-id)]
      (try
        (.explode level
                  nil
                  (double x)
                  (double y)
                  (double z)
                  (float radius)
                  (boolean fire?)
                  Level$ExplosionInteraction/MOB)
        true
        (catch Throwable _
          (.explode level
                    nil
                    (double x)
                    (double y)
                    (double z)
                    (float radius)
                    Level$ExplosionInteraction/MOB)
          true)))
    (catch Exception e
      (log/warn "[fabric] Failed to create explosion:" (ex-message e))
      false)))

(defn- find-entities-in-radius-impl [world-id x y z radius]
  (try
    (when-let [^ServerLevel level (get-level world-id)]
      (core/entities-in-radius
        level
        x y z radius
        (fn [^ServerLevel l ^AABB aabb]
          (.getEntities l nil aabb))
        (fn [entity]
          (str (.getDescriptionId (.getType entity))))))
    (catch Exception e
      (log/warn "[fabric] Failed to find entities:" (ex-message e))
      [])))

(defn- find-blocks-in-radius-impl [world-id x y z radius block-predicate]
  (try
    (when-let [^ServerLevel level (get-level world-id)]
      (core/find-blocks-in-radius-in-level
        level
        x y z radius block-predicate
        (fn [block _block-state]
          (str (.getDescriptionId block)))))
    (catch Exception e
      (log/warn "[fabric] Failed to find blocks:" (ex-message e))
      [])))

(defn- play-sound-impl! [world-id x y z sound-id source volume pitch]
  (try
    (when-let [^ServerLevel level (get-level world-id)]
      (boolean (core/play-sound-in-level! level x y z sound-id source volume pitch)))
    (catch Exception e
      (log/warn "[fabric] Failed to play world sound:" (ex-message e))
      false)))

(defn fabric-world-effects []
  (reify pwe/IWorldEffects
    (spawn-lightning! [_ world-id x y z]
      (spawn-lightning-impl! world-id x y z))
    (create-explosion! [_ world-id x y z radius fire?]
      (create-explosion-impl! world-id x y z radius fire?))
    (find-entities-in-radius [_ world-id x y z radius]
      (or (find-entities-in-radius-impl world-id x y z radius) []))
    (find-blocks-in-radius [_ world-id x y z radius block-predicate]
      (or (find-blocks-in-radius-impl world-id x y z radius block-predicate) []))
    (play-sound! [_ world-id x y z sound-id source volume pitch]
      (boolean (play-sound-impl! world-id x y z sound-id source volume pitch)))))

(defn install-world-effects! []
  (if-not (compare-and-set! installed? false true)
    (log/info "Fabric world effects already installed, skipping")
    (do
      (server-context/install-server-context!)
      (alter-var-root #'pwe/*world-effects*
                      (constantly (fabric-world-effects)))
      (log/info "Fabric world effects installed"))))

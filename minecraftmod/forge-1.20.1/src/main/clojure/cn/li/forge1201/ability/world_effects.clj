(ns cn.li.forge1201.ability.world-effects
  "Forge implementation of IWorldEffects protocol."
  (:require [cn.li.mcmod.platform.world-effects :as pwe]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.server MinecraftServer]
           [net.minecraft.server.level ServerLevel]
           [net.minecraft.world.entity Entity LivingEntity]
           [net.minecraft.world.level Level Level$ExplosionInteraction]
           [net.minecraft.core BlockPos]
           [net.minecraft.resources ResourceLocation]
           [net.minecraft.world.phys AABB Vec3]
           [net.minecraft.world.level.block Block]
           [net.minecraftforge.server ServerLifecycleHooks]))

(set! *warn-on-reflection* true)

(defn- get-server ^MinecraftServer []
  (ServerLifecycleHooks/getCurrentServer))

(defn- get-level ^ServerLevel [world-id]
  (when-let [^MinecraftServer server (get-server)]
    (let [res-loc (ResourceLocation. world-id)]
      (.getLevel server res-loc))))

(defn- spawn-lightning-impl! [world-id x y z]
  (try
    (when-let [^ServerLevel level (get-level world-id)]
      ;; Use Class/forName to avoid loading EntityType at AOT compile time (MC bootstrap issue).
      ;; Reflective .create call avoids loading Level.class at compile time (also triggers bootstrap).
      (let [et-class (Class/forName "net.minecraft.world.entity.EntityType")
            lightning-bolt (.get (.getDeclaredField et-class "LIGHTNING_BOLT") nil)
            lightning (.create lightning-bolt level)]
        (when lightning
          (.moveTo ^Entity lightning (double x) (double y) (double z))
          (.addFreshEntity level ^Entity lightning)
          true)))
    (catch Exception e
      (log/warn "Failed to spawn lightning:" (ex-message e))
      false)))

(defn- create-explosion-impl! [world-id x y z radius fire?]
  (try
    (when-let [^ServerLevel level (get-level world-id)]
      (.explode level nil x y z (float radius)
                (if fire?
                  Level$ExplosionInteraction/MOB
                  Level$ExplosionInteraction/NONE))
      true)
    (catch Exception e
      (log/warn "Failed to create explosion:" (ex-message e))
      false)))

(defn- find-entities-in-radius-impl [world-id x y z radius]
  (try
    (when-let [^ServerLevel level (get-level world-id)]
      (let [aabb (AABB. (- x radius) (- y radius) (- z radius)
                        (+ x radius) (+ y radius) (+ z radius))
            entities (.getEntitiesOfClass level LivingEntity aabb)]
        (mapv (fn [^LivingEntity entity]
                (let [pos (.position entity)]
                  {:uuid (str (.getUUID entity))
                   :x (.x pos)
                   :y (.y pos)
                   :z (.z pos)
                   :type (str (.getDescriptionId (.getType entity)))}))
              entities)))
    (catch Exception e
      (log/warn "Failed to find entities:" (ex-message e))
      [])))

(defn- find-blocks-in-radius-impl [world-id x y z radius block-predicate]
  (try
    (when-let [^ServerLevel level (get-level world-id)]
      (let [r (int radius)
            results (atom [])]
        (doseq [dx (range (- r) (inc r))
                dy (range (- r) (inc r))
                dz (range (- r) (inc r))]
          (let [bx (+ (int x) dx)
                by (+ (int y) dy)
                bz (+ (int z) dz)
                dist (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz)))]
            (when (<= dist radius)
              (let [pos (BlockPos. bx by bz)
                    block-state (.getBlockState level pos)
                    block (.getBlock block-state)
                    block-id (str (.getDescriptionId block))]
                (when (block-predicate block-id)
                  (swap! results conj {:x bx :y by :z bz :block-id block-id}))))))
        @results))
    (catch Exception e
      (log/warn "Failed to find blocks:" (ex-message e))
      [])))

(defn forge-world-effects []
  (reify pwe/IWorldEffects
    (spawn-lightning! [_ world-id x y z]
      (spawn-lightning-impl! world-id x y z))
    (create-explosion! [_ world-id x y z radius fire?]
      (create-explosion-impl! world-id x y z radius fire?))
    (find-entities-in-radius [_ world-id x y z radius]
      (find-entities-in-radius-impl world-id x y z radius))
    (find-blocks-in-radius [_ world-id x y z radius block-predicate]
      (find-blocks-in-radius-impl world-id x y z radius block-predicate))))

(defn install-world-effects! []
  (alter-var-root #'pwe/*world-effects*
                  (constantly (forge-world-effects)))
  (log/info "Forge world effects installed"))

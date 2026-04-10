(ns cn.li.forge1201.ability.world-effects
  "Forge implementation of IWorldEffects protocol."
  (:require [cn.li.mcmod.platform.world-effects :as pwe]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.server MinecraftServer]
           [net.minecraft.server.level ServerLevel]
           [net.minecraft.core BlockPos]
           [net.minecraft.resources ResourceLocation]
           [net.minecraft.world.entity Entity EntityType LivingEntity]
           [net.minecraft.world.phys AABB Vec3]
           [net.minecraftforge.server ServerLifecycleHooks]))


(defn- load-class-no-init ^Class [class-name]
  (Class/forName class-name false (.getContextClassLoader (Thread/currentThread))))

(defn- get-server ^MinecraftServer []
  (ServerLifecycleHooks/getCurrentServer))

(defn- get-level ^ServerLevel [world-id]
  (when-let [^MinecraftServer server (get-server)]
    (let [res-loc (ResourceLocation. world-id)]
      (.getLevel server res-loc))))

(defn- spawn-lightning-impl! [world-id x y z]
  (try
    (when-let [^ServerLevel level (get-level world-id)]
      ;; Resolve EntityType lazily so checkClojure does not trigger registry bootstrap.
      (let [et-class (load-class-no-init "net.minecraft.world.entity.EntityType")
        ^EntityType lightning-bolt (.get (.getDeclaredField et-class "LIGHTNING_BOLT") nil)
        ^Entity lightning (.create lightning-bolt level)]
        (when lightning
          (.moveTo lightning (double x) (double y) (double z))
          (.addFreshEntity level lightning)
          true)))
    (catch Exception e
      (log/warn "Failed to spawn lightning:" (ex-message e))
      false)))
(defn- create-explosion-impl! [world-id x y z radius fire?]
  (try
    (when-let [^ServerLevel level (get-level world-id)]
      (let [explosion-interaction-class (load-class-no-init "net.minecraft.world.level.Level$ExplosionInteraction")
            enum-name (if fire? "MOB" "NONE")
            interaction (java.lang.Enum/valueOf explosion-interaction-class enum-name)]
        (.explode level nil x y z (float radius) interaction)
        true))
    (catch Exception e
      (log/warn "Failed to create explosion:" (ex-message e))
      false)))
(defn- find-entities-in-radius-impl [world-id x y z radius]
  (try
    (when-let [^ServerLevel level (get-level world-id)]
      (let [aabb (AABB. (- x radius) (- y radius) (- z radius)
                        (+ x radius) (+ y radius) (+ z radius))
            living-entity-class (load-class-no-init "net.minecraft.world.entity.LivingEntity")
            entities (.getEntitiesOfClass level living-entity-class aabb)]
            (mapv (fn [^LivingEntity entity]
              (let [^Vec3 pos (.position entity)]
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

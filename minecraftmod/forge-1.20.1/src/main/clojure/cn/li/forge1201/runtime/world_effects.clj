(ns cn.li.forge1201.runtime.world-effects
  "Forge implementation of IWorldEffects protocol."
  (:require [cn.li.mcmod.platform.world-effects :as pwe]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.server MinecraftServer]
           [cn.li.forge1201.bridge ForgeRuntimeBridge]
           [net.minecraft.server.level ServerLevel]
           [net.minecraft.core BlockPos]
           [net.minecraft.core.registries BuiltInRegistries]
           [net.minecraft.resources ResourceLocation]
           [net.minecraft.sounds SoundSource SoundEvent]
           [net.minecraft.world.entity LivingEntity]
           [net.minecraft.world.phys AABB Vec3]
           [net.minecraftforge.server ServerLifecycleHooks]))

(defn- get-server ^MinecraftServer []
  (ServerLifecycleHooks/getCurrentServer))

(defn- get-level ^ServerLevel [world-id]
  (when-let [^MinecraftServer server (get-server)]
    (let [res-loc (ResourceLocation. world-id)]
      (.getLevel server res-loc))))

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
      (let [aabb (AABB. (- x radius) (- y radius) (- z radius)
                        (+ x radius) (+ y radius) (+ z radius))
            entities (ForgeRuntimeBridge/getLivingEntitiesInAabb level aabb)]
            (mapv (fn [^LivingEntity entity]
              (let [^Vec3 pos (.position entity)]
                  {:uuid (str (.getUUID entity))
                   :x (.x pos)
                   :y (.y pos)
                   :z (.z pos)
                   :width (double (.getBbWidth entity))
                   :height (double (.getBbHeight entity))
                   :eye-height (double (.getEyeHeight entity))
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

(def ^:private sound-source-map
  {:ambient SoundSource/AMBIENT
   :players SoundSource/PLAYERS
   :blocks SoundSource/BLOCKS
   :hostile SoundSource/HOSTILE
   :neutral SoundSource/NEUTRAL
   :music SoundSource/MUSIC
   :master SoundSource/MASTER
   :weather SoundSource/WEATHER
   :records SoundSource/RECORDS})

(defn- resolve-sound-source [source]
  (or (get sound-source-map source)
      SoundSource/AMBIENT))

(defn- play-sound-impl! [world-id x y z sound-id source volume pitch]
  (try
    (when-let [^ServerLevel level (get-level world-id)]
      (let [^SoundEvent sound-event (.get BuiltInRegistries/SOUND_EVENT (ResourceLocation. ^String sound-id))]
        (when sound-event
          (.playSound level
                      nil
                      (double x)
                      (double y)
                      (double z)
                      sound-event
                      (resolve-sound-source source)
                      (float volume)
                      (float pitch))
          true)))
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

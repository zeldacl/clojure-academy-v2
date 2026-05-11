(ns cn.li.mc1201.runtime.world-effects-core
  "Shared Minecraft-side world effects helpers (no loader API imports)."
  (:import [net.minecraft.core BlockPos]
           [net.minecraft.core.registries BuiltInRegistries]
           [net.minecraft.resources ResourceLocation]
           [net.minecraft.sounds SoundSource SoundEvent]
           [net.minecraft.world.entity Entity LivingEntity]
           [net.minecraft.world.entity.item ItemEntity]
           [net.minecraft.world.entity.monster Monster]
           [net.minecraft.world.entity.projectile Projectile]
           [net.minecraft.world.level Level]
           [net.minecraft.world.phys AABB Vec3]))

(defn entity->map
  [^Entity entity resolve-entity-id-fn]
  (let [^Vec3 pos (.position entity)]
    {:uuid (str (.getUUID entity))
     :x (.x pos)
     :y (.y pos)
     :z (.z pos)
     :width (double (.getBbWidth entity))
     :height (double (.getBbHeight entity))
     :eye-height (if (instance? LivingEntity entity)
                   (double (.getEyeHeight ^LivingEntity entity))
                   (double (.getBbHeight entity)))
     :entity-id (when resolve-entity-id-fn (resolve-entity-id-fn entity))
     :type (str (.getDescriptionId (.getType entity)))
     :living? (instance? LivingEntity entity)
     :mob? (instance? Monster entity)
     :item? (instance? ItemEntity entity)
     :projectile? (instance? Projectile entity)}))

(defn find-blocks-in-radius-in-level
  [^Level level x y z radius block-predicate block-id-fn]
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
                block-id (block-id-fn block block-state)]
            (when (block-predicate block-id)
              (swap! results conj {:x bx :y by :z bz :block-id block-id}))))))
    @results))

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

(defn resolve-sound-source
  [source]
  (or (get sound-source-map source)
      SoundSource/AMBIENT))

(defn play-sound-in-level!
  [^Level level x y z sound-id source volume pitch]
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

(defn entities-in-radius
  [^Level level x y z radius get-entities-fn resolve-entity-id-fn]
  (let [aabb (AABB. (- x radius) (- y radius) (- z radius)
                    (+ x radius) (+ y radius) (+ z radius))
        entities (get-entities-fn level aabb)]
    (mapv #(entity->map % resolve-entity-id-fn) entities)))

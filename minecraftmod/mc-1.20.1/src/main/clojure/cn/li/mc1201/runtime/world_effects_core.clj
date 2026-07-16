(ns cn.li.mc1201.runtime.world-effects-core
  "Shared Minecraft-side world effects helpers (no loader API imports)."
  (:import [cn.li.mc1201.entity ScriptedEffectEntity]
           [net.minecraft.core BlockPos]
           [net.minecraft.core.registries BuiltInRegistries]
           [net.minecraft.resources ResourceLocation]
           [net.minecraft.sounds SoundSource SoundEvent]
           [net.minecraft.world.entity Entity EntityType LivingEntity]
           [net.minecraft.world.entity.item ItemEntity]
           [net.minecraft.world.item Item ItemStack]
           [net.minecraft.world.entity.monster Monster]
           [net.minecraft.world.entity.projectile Projectile]
           [net.minecraft.world.level Level]
           [net.minecraft.world.phys AABB Vec3]))

(defn entity->map
  [^Entity entity resolve-entity-id-fn]
  (let [^Vec3 pos (.position entity)
        scripted? (instance? ScriptedEffectEntity entity)
        ^ScriptedEffectEntity scripted-entity (when scripted? entity)
        age-ticks (when scripted? (.getAgeTicks scripted-entity))
        motion-progress (when (and scripted? (.hasMotionProgress scripted-entity))
              (.getMotionProgress scripted-entity))]
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
    :projectile? (instance? Projectile entity)
    :age-ticks age-ticks
    :motion-progress motion-progress}))

(defn find-blocks-in-radius-in-level
  [^Level level x y z radius block-predicate block-id-fn]
  (let [r (int radius)
        results (transient [])]
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
              (conj! results {:x bx :y by :z bz :block-id block-id}))))))
    (persistent! results)))

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

(defn entities-in-aabb
  [^Level level min-x min-y min-z max-x max-y max-z get-entities-fn resolve-entity-id-fn]
  (let [aabb (AABB. min-x min-y min-z max-x max-y max-z)
        entities (get-entities-fn level aabb)]
    (mapv #(entity->map % resolve-entity-id-fn) entities)))

(defn spawn-projectile-in-level!
  [^Level level projectile-spec resolve-entity-id-fn get-entity-by-uuid-fn]
  (let [{:keys [entity-id x y z vx vy vz owner-uuid]} projectile-spec]
    (try
      (let [^EntityType entity-type (.get BuiltInRegistries/ENTITY_TYPE (ResourceLocation. (str entity-id)))]
        (if-not entity-type
          {:success? false}
          (if-let [^Entity entity (.create entity-type level)]
            (do
              (.moveTo entity
                       (double (or x 0.0))
                       (double (or y 0.0))
                       (double (or z 0.0))
                       (.getYRot entity)
                       (.getXRot entity))
              (when (instance? Projectile entity)
                (when-let [owner (when (and owner-uuid get-entity-by-uuid-fn)
                                   (get-entity-by-uuid-fn level owner-uuid))]
                  (.setOwner ^Projectile entity owner)))
              (.setDeltaMovement entity
                                 (double (or vx 0.0))
                                 (double (or vy 0.0))
                                 (double (or vz 0.0)))
              (if (.addFreshEntity level entity)
                {:success? true
                 :uuid (str (.getUUID entity))
                 :entity-id (or (when resolve-entity-id-fn (resolve-entity-id-fn entity))
                                (str entity-id))}
                {:success? false}))
            {:success? false})))
      (catch Exception _
        {:success? false}))))

(defn spawn-item-stack-at!
  "Spawn an ItemEntity in the world at the given position.
  item-stack is a platform item map with :id (registry name string) and :count (int).
  Matching upstream implementation: world.spawnEntity(new EntityItem(world, x, y, z, itemStack))
  Returns true on success, false on failure."
  [^Entity player world-id x y z item-stack]
  (try
    (when-let [^Level level (some-> player .level)]
      (let [item-id (:id item-stack)
            count (int (max 1 (or (:count item-stack) 1)))
            ^Item item (.get BuiltInRegistries/ITEM (ResourceLocation. (str item-id)))
            ^ItemStack mc-stack (ItemStack. item count)]
        (let [^ItemEntity entity (ItemEntity. level (double x) (double y) (double z) mc-stack)]
          (.setPickUpDelay entity 10)  ;; vanilla default, matching original EntityItem constructor
          (.addFreshEntity level entity)
          true)))
    (catch Exception _
      false)))

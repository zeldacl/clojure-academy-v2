(ns cn.li.mc1201.client.effects.particle
  "CLIENT-ONLY shared particle effect bridge for Minecraft 1.20.1."
  (:require [cn.li.mcmod.platform.power-runtime :as power-runtime]
            [cn.li.mcmod.util.log :as log]
            [clojure.string :as str])
  (:import [net.minecraft.client Minecraft]
           [net.minecraft.client.multiplayer ClientLevel]
           [net.minecraft.core BlockPos]
           [net.minecraft.core.particles BlockParticleOption ParticleTypes ParticleType]
           [net.minecraft.core.registries BuiltInRegistries]
           [net.minecraft.resources ResourceLocation]
           [net.minecraft.world.level.block Block]
           [net.minecraft.world.level.block.state BlockState]))

(defn- ->resource-location
  [particle-type]
  (cond
    (keyword? particle-type)
    (if-let [ns (namespace particle-type)]
      (ResourceLocation. ns (name particle-type))
      (ResourceLocation. "minecraft" (name particle-type)))

    (string? particle-type)
    (if (str/includes? particle-type ":")
      (ResourceLocation. ^String particle-type)
      (ResourceLocation. "minecraft" ^String particle-type))

    :else nil))

(defn- resolve-particle
  [^ClientLevel level particle-cmd]
  (let [{:keys [particle-type block-id x y z]} particle-cmd]
    (if (= :block-crack particle-type)
      (let [block-registry ^net.minecraft.core.Registry BuiltInRegistries/BLOCK
            ^BlockState state (or (when block-id
                                     (some-> ^Block (.get block-registry ^ResourceLocation (ResourceLocation. ^String block-id))
                                             (.defaultBlockState)))
                                   (.getBlockState level (BlockPos. (int (Math/floor (double x)))
                                                                    (int (Math/floor (double y)))
                                                                    (int (Math/floor (double z))))))]
        (if (.isAir state)
          ParticleTypes/SMOKE
          (BlockParticleOption. ParticleTypes/BLOCK state)))
      (let [particle-registry ^net.minecraft.core.Registry BuiltInRegistries/PARTICLE_TYPE
            ptype (some-> particle-type ->resource-location (as-> rl ^ParticleType (.get particle-registry ^ResourceLocation rl)))]
        (or ptype ParticleTypes/SMOKE)))))

(defn spawn-particle-effect!
  [particle-cmd]
  (try
    (when-let [^Minecraft mc (Minecraft/getInstance)]
      (when-let [^ClientLevel level (.level mc)]
        (let [{:keys [x y z count speed offset-x offset-y offset-z]} particle-cmd
              particle (resolve-particle level particle-cmd)]
          (.addParticle level particle
                        x y z
                        (* offset-x speed)
                        (* offset-y speed)
                        (* offset-z speed))
          (dotimes [_ (dec count)]
            (let [dx (- (rand offset-x) (/ offset-x 2))
                  dy (- (rand offset-y) (/ offset-y 2))
                  dz (- (rand offset-z) (/ offset-z 2))]
              (.addParticle level particle
                            (+ x dx) (+ y dy) (+ z dz)
                            (* dx speed)
                            (* dy speed)
                            (* dz speed)))))))
    (catch Exception e
      (log/error "Error spawning particle effect" e))))

(defn tick-particles!
  []
  (try
    (doseq [particle-cmd (power-runtime/client-poll-particle-effects)]
      (spawn-particle-effect! particle-cmd))
    (catch Exception e
      (log/error "Error in particle tick" e))))

(defn init!
  []
  (log/info "Shared particle effect bridge initialized"))

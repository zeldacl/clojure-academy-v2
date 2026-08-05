(ns cn.li.mc262.client.effects.particle
  "CLIENT-ONLY shared particle effect bridge for Minecraft 26.2."
  (:require [cn.li.mc262.client.session :as client-session]
            [cn.li.mcmod.hooks.core :as power-runtime]
            [cn.li.mcmod.util.log :as log]
            [clojure.string :as str])
  (:import [net.minecraft.client Minecraft]
           [net.minecraft.client.multiplayer ClientLevel]
           [net.minecraft.core BlockPos]
           [net.minecraft.core.particles BlockParticleOption ParticleTypes ParticleType]
           [net.minecraft.core.registries BuiltInRegistries]
           [net.minecraft.resources Identifier]
           [cn.li.mcver ResourceLocations]
           [net.minecraft.world.level.block Block]
           [net.minecraft.world.level.block.state BlockState]))

(defn- ->resource-location
  [particle-type]
  (cond
    (keyword? particle-type)
    (if-let [ns (namespace particle-type)]
      (ResourceLocations/of ns (name particle-type))
      (ResourceLocations/of "minecraft" (name particle-type)))

    (string? particle-type)
    (if (str/includes? particle-type ":")
      (ResourceLocations/parse ^String particle-type)
      (ResourceLocations/of "minecraft" ^String particle-type))

    :else nil))

(defn- resolve-particle
  [^ClientLevel level particle-cmd]
  (let [{:keys [particle-type block-id x y z]} particle-cmd]
    (if (= :block-crack particle-type)
      (let [block-registry BuiltInRegistries/BLOCK
            ^BlockState state (or (when block-id
                                     (some-> ^Block (.getValue block-registry
                                                               ^Identifier (ResourceLocations/parse ^String block-id))
                                             (.defaultBlockState)))
                                   (.getBlockState level (BlockPos. (int (Math/floor (double x)))
                                                                    (int (Math/floor (double y)))
                                                                    (int (Math/floor (double z))))))]
        (if (.isAir state)
          ParticleTypes/SMOKE
          (BlockParticleOption. ParticleTypes/BLOCK state)))
      (let [particle-registry BuiltInRegistries/PARTICLE_TYPE
            ptype (some-> particle-type
                          ->resource-location
                          (as-> rl ^ParticleType (.getValue particle-registry ^Identifier rl)))]
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
    (when-let [owner (client-session/current-local-player-owner)]
      (doseq [particle-cmd (power-runtime/client-poll-particle-effects owner)]
        (spawn-particle-effect! particle-cmd)))
    (catch Exception e
      (log/error "Error in particle tick" e))))

(defn init!
  []
  (log/info "Shared particle effect bridge initialized"))

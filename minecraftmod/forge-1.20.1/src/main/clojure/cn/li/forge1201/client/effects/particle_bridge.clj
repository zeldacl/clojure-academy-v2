(ns cn.li.forge1201.client.effects.particle-bridge
  "CLIENT-ONLY particle effect bridge (Forge layer)."
  (:require [cn.li.mcmod.platform.ability-lifecycle :as ability-runtime]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.client Minecraft]
           [net.minecraft.core BlockPos]
           [net.minecraft.core.particles BlockParticleOption ParticleTypes]
           [cn.li.forge1201.bridge ForgeRuntimeBridge]
           [net.minecraft.client.multiplayer ClientLevel]))

(defn- resolve-particle
  [^ClientLevel level particle-cmd]
  (let [{:keys [particle-type block-id x y z]} particle-cmd]
    (if (= :block-crack particle-type)
      (let [state (or (when block-id
                        (some-> (ForgeRuntimeBridge/getBlockById ^String block-id)
                                (.defaultBlockState)))
                      (.getBlockState level (BlockPos. (int (Math/floor (double x)))
                                                       (int (Math/floor (double y)))
                                                       (int (Math/floor (double z))))))]
        (if (.isAir state)
          ParticleTypes/SMOKE
          (BlockParticleOption. ParticleTypes/BLOCK state)))
      (ForgeRuntimeBridge/getParticleType (name particle-type)))))

(defn- spawn-particle-effect
  "Spawn a particle effect in the world."
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
          ;; Spawn multiple particles
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
  "Poll and spawn queued particle effects. Called every client tick."
  []
  (try
    (doseq [particle-cmd (ability-runtime/client-poll-particle-effects)]
      (spawn-particle-effect particle-cmd))
    (catch Exception e
      (log/error "Error in particle tick" e))))

(defn init!
  "Initialize particle bridge."
  []
  (log/info "Particle effect bridge initialized"))

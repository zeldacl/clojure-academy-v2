(ns cn.li.forge1201.client.effects.particle-bridge
  "CLIENT-ONLY particle effect bridge (Forge layer)."
  (:require [cn.li.ac.ability.client.effects.particles :as ac-particles]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.client Minecraft]
           [net.minecraft.core.particles ParticleTypes]
           [net.minecraft.client.multiplayer ClientLevel]))

(set! *warn-on-reflection* true)

(defn- get-particle-type
  "Map AC particle type keyword to Minecraft ParticleType."
  [particle-type-kw]
  (case particle-type-kw
    :electric-spark ParticleTypes/ELECTRIC_SPARK
    :portal ParticleTypes/PORTAL
    :flame ParticleTypes/FLAME
    :end-rod ParticleTypes/END_ROD
    :enchant ParticleTypes/ENCHANT
    :angry-villager ParticleTypes/ANGRY_VILLAGER
    :totem-of-undying ParticleTypes/TOTEM_OF_UNDYING
    :generic ParticleTypes/GLOW
    ParticleTypes/GLOW))

(defn- spawn-particle-effect
  "Spawn a particle effect in the world."
  [particle-cmd]
  (try
    (when-let [^Minecraft mc (Minecraft/getInstance)]
      (when-let [^ClientLevel level (.level mc)]
        (let [{:keys [particle-type x y z count speed offset-x offset-y offset-z]} particle-cmd
              particle (get-particle-type particle-type)]
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
    (doseq [particle-cmd (ac-particles/poll-particle-effects!)]
      (spawn-particle-effect particle-cmd))
    (catch Exception e
      (log/error "Error in particle tick" e))))

(defn init!
  "Initialize particle bridge."
  []
  (log/info "Particle effect bridge initialized"))

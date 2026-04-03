(ns cn.li.forge1201.ability.potion-effects
  "Forge implementation of IPotionEffects protocol."
  (:require [cn.li.mcmod.platform.potion-effects :as ppe]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.server MinecraftServer]
           [net.minecraft.server.level ServerPlayer]
           [net.minecraft.world.effect MobEffects MobEffectInstance]
           [net.minecraftforge.server ServerLifecycleHooks]
           [java.util UUID]))

(set! *warn-on-reflection* true)

(defn- get-server ^MinecraftServer []
  (ServerLifecycleHooks/getCurrentServer))

(defn- get-player-by-uuid [uuid-str]
  (try
    (when-let [^MinecraftServer server (get-server)]
      (let [uuid (UUID/fromString uuid-str)]
        (.getPlayer (.getPlayerList server) uuid)))
    (catch Exception e
      (log/warn "Failed to get player by UUID:" uuid-str (ex-message e))
      nil)))

(defn- get-mob-effect [effect-type]
  (case effect-type
    :speed MobEffects/MOVEMENT_SPEED
    :slowness MobEffects/MOVEMENT_SLOWDOWN
    :jump-boost MobEffects/JUMP
    :regeneration MobEffects/REGENERATION
    :strength MobEffects/DAMAGE_BOOST
    :resistance MobEffects/DAMAGE_RESISTANCE
    :hunger MobEffects/HUNGER
    :blindness MobEffects/BLINDNESS
    :haste MobEffects/DIG_SPEED
    :mining-fatigue MobEffects/DIG_SLOWDOWN
    :nausea MobEffects/CONFUSION
    :invisibility MobEffects/INVISIBILITY
    :night-vision MobEffects/NIGHT_VISION
    :weakness MobEffects/WEAKNESS
    :poison MobEffects/POISON
    :wither MobEffects/WITHER
    :health-boost MobEffects/HEALTH_BOOST
    :absorption MobEffects/ABSORPTION
    :saturation MobEffects/SATURATION
    :glowing MobEffects/GLOWING
    :levitation MobEffects/LEVITATION
    :luck MobEffects/LUCK
    :unluck MobEffects/UNLUCK
    :slow-falling MobEffects/SLOW_FALLING
    :conduit-power MobEffects/CONDUIT_POWER
    :dolphins-grace MobEffects/DOLPHINS_GRACE
    :bad-omen MobEffects/BAD_OMEN
    :hero-of-the-village MobEffects/HERO_OF_THE_VILLAGE
    :darkness MobEffects/DARKNESS
    (do
      (log/warn "Unknown potion effect type:" effect-type)
      nil)))

(defn- apply-potion-effect-impl! [player-uuid effect-type duration amplifier]
  (try
    (when-let [^ServerPlayer player (get-player-by-uuid player-uuid)]
      (when-let [mob-effect (get-mob-effect effect-type)]
        (let [effect-instance (MobEffectInstance. mob-effect (int duration) (int amplifier))]
          (.addEffect player effect-instance)
          true)))
    (catch Exception e
      (log/warn "Failed to apply potion effect:" (ex-message e))
      false)))

(defn- remove-potion-effect-impl! [player-uuid effect-type]
  (try
    (when-let [^ServerPlayer player (get-player-by-uuid player-uuid)]
      (when-let [mob-effect (get-mob-effect effect-type)]
        (.removeEffect player mob-effect)
        true))
    (catch Exception e
      (log/warn "Failed to remove potion effect:" (ex-message e))
      false)))

(defn- has-potion-effect-impl? [player-uuid effect-type]
  (try
    (when-let [^ServerPlayer player (get-player-by-uuid player-uuid)]
      (when-let [mob-effect (get-mob-effect effect-type)]
        (.hasEffect player mob-effect)))
    (catch Exception e
      (log/warn "Failed to check potion effect:" (ex-message e))
      false)))

(defn- clear-all-effects-impl! [player-uuid]
  (try
    (when-let [^ServerPlayer player (get-player-by-uuid player-uuid)]
      (.removeAllEffects player)
      true)
    (catch Exception e
      (log/warn "Failed to clear all effects:" (ex-message e))
      false)))

(defn forge-potion-effects []
  (reify ppe/IPotionEffects
    (apply-potion-effect! [_ player-uuid effect-type duration amplifier]
      (apply-potion-effect-impl! player-uuid effect-type duration amplifier))
    (remove-potion-effect! [_ player-uuid effect-type]
      (remove-potion-effect-impl! player-uuid effect-type))
    (has-potion-effect? [_ player-uuid effect-type]
      (has-potion-effect-impl? player-uuid effect-type))
    (clear-all-effects! [_ player-uuid]
      (clear-all-effects-impl! player-uuid))))

(defn install-potion-effects! []
  (alter-var-root #'ppe/*potion-effects*
                  (constantly (forge-potion-effects)))
  (log/info "Forge potion effects installed"))

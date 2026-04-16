(ns cn.li.forge1201.runtime.potion-effects
  "Forge implementation of IPotionEffects protocol."
  (:require [cn.li.mcmod.platform.potion-effects :as ppe]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.forge1201.bridge ForgeRuntimeBridge]
           [net.minecraft.server MinecraftServer]
           [net.minecraft.server.level ServerPlayer]
           [net.minecraft.world.effect MobEffect MobEffectInstance]
           [net.minecraftforge.server ServerLifecycleHooks]
           [java.util UUID]))


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

;; Lazy map of effect keywords -> MobEffect instances, loaded at runtime to avoid
;; triggering MC bootstrap during Clojure AOT compilation.
;; MobEffects static fields are accessed via ForgeRuntimeBridge so Loom can remap them.
(defonce ^:private mob-effects-cache
  (delay
    {:speed               (ForgeRuntimeBridge/getMobEffect "MOVEMENT_SPEED")
     :slowness            (ForgeRuntimeBridge/getMobEffect "MOVEMENT_SLOWDOWN")
     :jump-boost          (ForgeRuntimeBridge/getMobEffect "JUMP")
     :regeneration        (ForgeRuntimeBridge/getMobEffect "REGENERATION")
     :strength            (ForgeRuntimeBridge/getMobEffect "DAMAGE_BOOST")
     :resistance          (ForgeRuntimeBridge/getMobEffect "DAMAGE_RESISTANCE")
     :hunger              (ForgeRuntimeBridge/getMobEffect "HUNGER")
     :blindness           (ForgeRuntimeBridge/getMobEffect "BLINDNESS")
     :haste               (ForgeRuntimeBridge/getMobEffect "DIG_SPEED")
     :mining-fatigue      (ForgeRuntimeBridge/getMobEffect "DIG_SLOWDOWN")
     :nausea              (ForgeRuntimeBridge/getMobEffect "CONFUSION")
     :invisibility        (ForgeRuntimeBridge/getMobEffect "INVISIBILITY")
     :night-vision        (ForgeRuntimeBridge/getMobEffect "NIGHT_VISION")
     :weakness            (ForgeRuntimeBridge/getMobEffect "WEAKNESS")
     :poison              (ForgeRuntimeBridge/getMobEffect "POISON")
     :wither              (ForgeRuntimeBridge/getMobEffect "WITHER")
     :health-boost        (ForgeRuntimeBridge/getMobEffect "HEALTH_BOOST")
     :absorption          (ForgeRuntimeBridge/getMobEffect "ABSORPTION")
     :saturation          (ForgeRuntimeBridge/getMobEffect "SATURATION")
     :glowing             (ForgeRuntimeBridge/getMobEffect "GLOWING")
     :levitation          (ForgeRuntimeBridge/getMobEffect "LEVITATION")
     :luck                (ForgeRuntimeBridge/getMobEffect "LUCK")
     :unluck              (ForgeRuntimeBridge/getMobEffect "UNLUCK")
     :slow-falling        (ForgeRuntimeBridge/getMobEffect "SLOW_FALLING")
     :conduit-power       (ForgeRuntimeBridge/getMobEffect "CONDUIT_POWER")
     :dolphins-grace      (ForgeRuntimeBridge/getMobEffect "DOLPHINS_GRACE")
     :bad-omen            (ForgeRuntimeBridge/getMobEffect "BAD_OMEN")
     :hero-of-the-village (ForgeRuntimeBridge/getMobEffect "HERO_OF_THE_VILLAGE")
     :darkness            (ForgeRuntimeBridge/getMobEffect "DARKNESS")}))

(defn- get-mob-effect [effect-type]
  (let [effect (get @mob-effects-cache effect-type)]
    (when-not effect
      (log/warn "Unknown potion effect type:" effect-type))
    effect))

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

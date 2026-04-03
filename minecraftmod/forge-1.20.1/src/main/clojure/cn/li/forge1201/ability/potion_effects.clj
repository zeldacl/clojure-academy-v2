(ns cn.li.forge1201.ability.potion-effects
  "Forge implementation of IPotionEffects protocol."
  (:require [cn.li.mcmod.platform.potion-effects :as ppe]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.server MinecraftServer]
           [net.minecraft.server.level ServerPlayer]
           [net.minecraft.world.effect MobEffect MobEffectInstance]
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

;; Lazy map of effect keywords -> MobEffect instances, loaded at runtime to avoid
;; triggering MC bootstrap during Clojure AOT compilation.
(defonce ^:private mob-effects-cache
  (delay
    (let [cls (Class/forName "net.minecraft.world.effect.MobEffects")]
      {:speed               (.get (.getField cls "MOVEMENT_SPEED") nil)
       :slowness            (.get (.getField cls "MOVEMENT_SLOWDOWN") nil)
       :jump-boost          (.get (.getField cls "JUMP") nil)
       :regeneration        (.get (.getField cls "REGENERATION") nil)
       :strength            (.get (.getField cls "DAMAGE_BOOST") nil)
       :resistance          (.get (.getField cls "DAMAGE_RESISTANCE") nil)
       :hunger              (.get (.getField cls "HUNGER") nil)
       :blindness           (.get (.getField cls "BLINDNESS") nil)
       :haste               (.get (.getField cls "DIG_SPEED") nil)
       :mining-fatigue      (.get (.getField cls "DIG_SLOWDOWN") nil)
       :nausea              (.get (.getField cls "CONFUSION") nil)
       :invisibility        (.get (.getField cls "INVISIBILITY") nil)
       :night-vision        (.get (.getField cls "NIGHT_VISION") nil)
       :weakness            (.get (.getField cls "WEAKNESS") nil)
       :poison              (.get (.getField cls "POISON") nil)
       :wither              (.get (.getField cls "WITHER") nil)
       :health-boost        (.get (.getField cls "HEALTH_BOOST") nil)
       :absorption          (.get (.getField cls "ABSORPTION") nil)
       :saturation          (.get (.getField cls "SATURATION") nil)
       :glowing             (.get (.getField cls "GLOWING") nil)
       :levitation          (.get (.getField cls "LEVITATION") nil)
       :luck                (.get (.getField cls "LUCK") nil)
       :unluck              (.get (.getField cls "UNLUCK") nil)
       :slow-falling        (.get (.getField cls "SLOW_FALLING") nil)
       :conduit-power       (.get (.getField cls "CONDUIT_POWER") nil)
       :dolphins-grace      (.get (.getField cls "DOLPHINS_GRACE") nil)
       :bad-omen            (.get (.getField cls "BAD_OMEN") nil)
       :hero-of-the-village (.get (.getField cls "HERO_OF_THE_VILLAGE") nil)
       :darkness            (.get (.getField cls "DARKNESS") nil)})))

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

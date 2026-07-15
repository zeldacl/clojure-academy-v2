(ns cn.li.mc1201.runtime.potion-effects-core
  "Loader-agnostic potion/mob-effect helpers.

  Uses BuiltInRegistries.MOB_EFFECT with vanilla ResourceLocations for
  both built-in and custom effects — no Forge-specific bridge needed."
  (:require [cn.li.mc1201.runtime.entity-query-core :as query-core]
            [cn.li.mcmod.protocol.metadata :as registry-metadata]
            [cn.li.mcmod.config :as modid]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.core.registries BuiltInRegistries]
           [net.minecraft.resources ResourceLocation]
           [net.minecraft.server MinecraftServer]
           [net.minecraft.server.level ServerPlayer]
           [net.minecraft.world.effect MobEffect MobEffectInstance]))

;; Vanilla effect keyword → minecraft: registry key
(def ^:private vanilla-effect-keys
  {:speed               "minecraft:speed"
   :slowness            "minecraft:slowness"
   :haste               "minecraft:haste"
   :mining-fatigue      "minecraft:mining_fatigue"
   :strength            "minecraft:strength"
   :jump-boost          "minecraft:jump_boost"
   :nausea              "minecraft:nausea"
   :regeneration        "minecraft:regeneration"
   :resistance          "minecraft:resistance"
   :fire-resistance     "minecraft:fire_resistance"
   :water-breathing     "minecraft:water_breathing"
   :invisibility        "minecraft:invisibility"
   :blindness           "minecraft:blindness"
   :night-vision        "minecraft:night_vision"
   :hunger              "minecraft:hunger"
   :weakness            "minecraft:weakness"
   :poison              "minecraft:poison"
   :wither              "minecraft:wither"
   :health-boost        "minecraft:health_boost"
   :absorption          "minecraft:absorption"
   :saturation          "minecraft:saturation"
   :glowing             "minecraft:glowing"
   :levitation          "minecraft:levitation"
   :luck                "minecraft:luck"
   :unluck              "minecraft:unluck"
   :slow-falling        "minecraft:slow_falling"
   :conduit-power       "minecraft:conduit_power"
   :dolphins-grace      "minecraft:dolphins_grace"
   :bad-omen            "minecraft:bad_omen"
   :hero-of-the-village "minecraft:hero_of_the_village"
   :darkness            "minecraft:darkness"})

(defn- get-mob-effect [effect-type]
  (let [vanilla-key (get vanilla-effect-keys effect-type)]
    (if vanilla-key
      (.get BuiltInRegistries/MOB_EFFECT (ResourceLocation. vanilla-key))
      ;; Custom registered effect
      (let [effect-id (name effect-type)
            known-custom? (some #(= effect-id (str %)) (registry-metadata/get-all-effect-ids))]
        (if-not known-custom?
          (do (log/warn "Unknown potion effect type:" effect-type) nil)
          (let [registry-name (registry-metadata/get-effect-registry-name effect-id)
                rl (ResourceLocation. modid/mod-id registry-name)
                effect (.get BuiltInRegistries/MOB_EFFECT rl)]
            (when-not effect
              (log/warn "Custom effect not found in MOB_EFFECT registry:" rl))
            effect))))))

(defn apply-potion-effect!
  [^MinecraftServer server player-uuid effect-type duration amplifier]
  (try
    (when-let [^ServerPlayer player (query-core/get-player-by-uuid server player-uuid)]
      (when-let [^MobEffect mob-effect (get-mob-effect effect-type)]
        (.addEffect player (MobEffectInstance. mob-effect (int duration) (int amplifier)))
        true))
    (catch Exception e
      (log/warn "Failed to apply potion effect:" (ex-message e))
      false)))

(defn remove-potion-effect!
  [^MinecraftServer server player-uuid effect-type]
  (try
    (when-let [^ServerPlayer player (query-core/get-player-by-uuid server player-uuid)]
      (when-let [^MobEffect mob-effect (get-mob-effect effect-type)]
        (.removeEffect player mob-effect)
        true))
    (catch Exception e
      (log/warn "Failed to remove potion effect:" (ex-message e))
      false)))

(defn has-potion-effect?
  [^MinecraftServer server player-uuid effect-type]
  (try
    (boolean
      (when-let [^ServerPlayer player (query-core/get-player-by-uuid server player-uuid)]
        (when-let [^MobEffect mob-effect (get-mob-effect effect-type)]
          (.hasEffect player mob-effect))))
    (catch Exception e
      (log/warn "Failed to check potion effect:" (ex-message e))
      false)))

(defn clear-all-effects!
  [^MinecraftServer server player-uuid]
  (try
    (when-let [^ServerPlayer player (query-core/get-player-by-uuid server player-uuid)]
      (.removeAllEffects player)
      true)
    (catch Exception e
      (log/warn "Failed to clear all effects:" (ex-message e))
      false)))

(defn create-potion-effects
  "Create an IPotionEffects adapter using a platform-provided server supplier."
  [get-server]
  {:apply-potion-effect! (fn [player-uuid effect-type duration amplifier]
                           (apply-potion-effect! (get-server) player-uuid effect-type duration amplifier))
   :remove-potion-effect! (fn [player-uuid effect-type]
                            (remove-potion-effect! (get-server) player-uuid effect-type))
   :has-potion-effect? (fn [player-uuid effect-type]
                         (has-potion-effect? (get-server) player-uuid effect-type))
   :clear-all-effects! (fn [player-uuid]
                         (clear-all-effects! (get-server) player-uuid))})

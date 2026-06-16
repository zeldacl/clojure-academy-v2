(ns cn.li.mc1201.client.effects.sound
  "CLIENT-ONLY shared sound effect bridge for Minecraft 1.20.1."
  (:require [cn.li.mc1201.client.session :as client-session]
            [cn.li.mcmod.hooks.core :as power-runtime]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.client Minecraft]
           [net.minecraft.client.sounds SoundManager]
           [net.minecraft.core.registries BuiltInRegistries]
           [net.minecraft.sounds SoundSource SoundEvent]
           [net.minecraft.resources ResourceLocation]))

(defn- play-sound-effect
  [sound-cmd]
  (try
    (when-let [^Minecraft mc (Minecraft/getInstance)]
      (when-let [player (.player mc)]
        (when-let [level (.level player)]
          (let [{:keys [sound-id volume pitch x y z]} sound-cmd
                sound-loc (ResourceLocation. ^String sound-id)
                pos-x (or x (.getX player))
                pos-y (or y (.getY player))
                pos-z (or z (.getZ player))
                ^SoundEvent sound-event (.get BuiltInRegistries/SOUND_EVENT sound-loc)]
            (when sound-event
              (.playLocalSound level pos-x pos-y pos-z
                               sound-event
                               SoundSource/PLAYERS
                               (float volume)
                               (float pitch)
                               false))))))
    (catch Exception e
      (log/error "Error playing sound effect" e))))

(defn tick-sounds!
  []
  (try
    (when-let [owner (client-session/current-local-player-owner)]
      (doseq [sound-cmd (power-runtime/client-poll-sound-effects owner)]
        (play-sound-effect sound-cmd)))
    (catch Exception e
      (log/error "Error in sound tick" e))))

;; -- SoundManager helpers (no :import — uses reflection to avoid
;;    compile-time class loading that triggers registry bootstrap) --

(defn- get-sound-manager []
  (when-let [^Minecraft mc (Minecraft/getInstance)]
    (.getSoundManager mc)))

(defn stop-sound!
  "Stop a playing sound by its ResourceLocation id (e.g. \"my_mod:em.arc_strong\")."
  [sound-id]
  (when-let [^SoundManager sm (get-sound-manager)]
    (let [loc (ResourceLocation. (namespace sound-id) (name sound-id))]
      (.stop sm ^ResourceLocation loc))))

(defn stop-all-media!
  "Stop all sounds in the PLAYERS category (covers media playback).
  Matches original AcademyCraft MediaBackend stop behavior."
  []
  (when-let [^SoundManager sm (get-sound-manager)]
    (.stop sm SoundSource/PLAYERS)))

(defn init!
  []
  (log/info "Shared sound effect bridge initialized"))

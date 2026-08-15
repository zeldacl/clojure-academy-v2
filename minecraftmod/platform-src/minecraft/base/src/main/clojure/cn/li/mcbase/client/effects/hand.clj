(ns cn.li.mcbase.client.effects.hand
  "Shared client hand-effect helpers for Minecraft 1.20.1."
  (:require [cn.li.mcbase.client.session :as client-session]
            [cn.li.platform.neutral.presentation :as presentation]
            [cn.li.platform.neutral.vfx :as vfx])
  (:import [net.minecraft.client Minecraft]
           [net.minecraft.client.player LocalPlayer]))

(defn tick-hand-effects!
  []
  (let [mc (Minecraft/getInstance)
        level (when mc (.level mc))
        tick-id (if level (.getGameTime level) (quot (System/currentTimeMillis) 50))]
    (vfx/tick! {:tick-id tick-id :delta-seconds 0.05})))

(defn apply-camera-pitch-deltas!
  [^LocalPlayer player]
  (when (and player (client-session/current-local-player-owner))
      (doseq [delta (presentation/drain-camera-pitch-deltas!
                    (client-session/current-local-player-owner))]
      (.setXRot player (+ (.getXRot player) (float delta))))))

(defn tick-and-apply-camera!
  [^LocalPlayer player]
  (tick-hand-effects!)
  (apply-camera-pitch-deltas! player))

(defn current-hand-transform
  []
  (presentation/hand-transform))

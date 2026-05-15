(ns cn.li.mc1201.client.effects.hand
  "Shared client hand-effect helpers for Minecraft 1.20.1."
  (:require [cn.li.mcmod.hooks.core :as power-runtime])
  (:import [net.minecraft.client.player LocalPlayer]))

(defn tick-hand-effects!
  []
  (power-runtime/client-tick-hand-effects!))

(defn apply-camera-pitch-deltas!
  [^LocalPlayer player]
  (when player
    (doseq [delta (power-runtime/client-drain-camera-pitch-deltas!)]
      (.setXRot player (+ (.getXRot player) (float delta))))))

(defn tick-and-apply-camera!
  [^LocalPlayer player]
  (tick-hand-effects!)
  (apply-camera-pitch-deltas! player))

(defn current-hand-transform
  []
  (power-runtime/client-current-hand-transform))

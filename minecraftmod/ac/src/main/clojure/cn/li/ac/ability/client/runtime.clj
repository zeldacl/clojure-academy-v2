(ns cn.li.ac.ability.client.runtime
  "AC-side runtime bridge for client key events.

  Keeps AC independent from Minecraft classes while delegating to platform runtime
  when available."
  (:require [cn.li.ac.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.platform.power-runtime :as power-runtime]))

(defn on-slot-key-down!
  [player-uuid key-idx]
  (client-bridge/on-slot-key-down! player-uuid key-idx))

(defn on-slot-key-tick!
  [player-uuid key-idx]
  (client-bridge/on-slot-key-tick! player-uuid key-idx))

(defn on-slot-key-up!
  [player-uuid key-idx]
  (client-bridge/on-slot-key-up! player-uuid key-idx))

(defn railgun-charge-visual-state
  [player-uuid]
  (power-runtime/client-railgun-charge-visual-state player-uuid))

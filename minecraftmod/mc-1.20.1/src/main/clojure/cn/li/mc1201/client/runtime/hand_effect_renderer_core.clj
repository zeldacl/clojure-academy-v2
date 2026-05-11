(ns cn.li.mc1201.client.runtime.hand-effect-renderer-core
  "Shared hand-effect runtime core facade.

  Keeps client runtime layering explicit while delegating to existing
  shared hand-effect implementation."
  (:require [cn.li.mc1201.client.effects.hand :as hand]))

(defn tick-hand-effects!
  []
  (hand/tick-hand-effects!))

(defn apply-camera-pitch-deltas!
  [player]
  (hand/apply-camera-pitch-deltas! player))

(defn tick-and-apply-camera!
  [player]
  (hand/tick-and-apply-camera! player))

(defn current-hand-transform
  []
  (hand/current-hand-transform))

(ns cn.li.mcmod.platform.raycast
  (:require [cn.li.mcmod.framework :as fw]))

(defn available?
  []
  (boolean (get-in @(fw/fw-atom) [:platform :raycast])))

(defn current
  []
  (get-in @(fw/fw-atom) [:platform :raycast]))

(defn- call
  [k & args]
  (when-let [f (get (current) k)]
    (apply f args)))

(defn raycast-blocks
  [world-id sx sy sz dx dy dz max-dist]
  (call :raycast-blocks world-id sx sy sz dx dy dz max-dist))

(defn raycast-entities
  [world-id sx sy sz dx dy dz max-dist]
  (call :raycast-entities world-id sx sy sz dx dy dz max-dist))

(defn raycast-combined
  [world-id sx sy sz dx dy dz max-dist]
  (call :raycast-combined world-id sx sy sz dx dy dz max-dist))

(defn player-look-vector
  [player-uuid]
  (call :get-player-look-vector player-uuid))

(defn player-position
  "Return {:x :y :z :eye-y} for a player UUID, or nil if not found.
  :x/:y/:z are body position (feet); :eye-y is eye-height Y."
  [player-uuid]
  (call :get-player-position player-uuid))

(defn raycast-from-player
  [player-uuid max-dist living-only?]
  (call :raycast-from-player player-uuid max-dist living-only?))

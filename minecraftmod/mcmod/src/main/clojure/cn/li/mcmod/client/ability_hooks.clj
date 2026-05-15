(ns cn.li.mcmod.client.ability-hooks
  "Client ability-FX hook surface (compat wrapper)."
  (:require [cn.li.mcmod.hooks.effects :as effects]))

(def client-poll-particle-effects effects/client-poll-particle-effects)
(def client-poll-sound-effects effects/client-poll-sound-effects)
(def client-enqueue-level-effect! effects/client-enqueue-level-effect!)
(def client-build-level-effect-plan effects/client-build-level-effect-plan)
(def client-tick-level-effects! effects/client-tick-level-effects!)
(def client-charge-coin-visual-state effects/client-charge-coin-visual-state)
(def client-railgun-charge-visual-state effects/client-railgun-charge-visual-state)
(def client-body-intensify-charge-visual-state effects/client-body-intensify-charge-visual-state)
(def client-current-charging-visual-state effects/client-current-charging-visual-state)
(def client-tick-hand-effects! effects/client-tick-hand-effects!)
(def client-drain-camera-pitch-deltas! effects/client-drain-camera-pitch-deltas!)
(def client-current-hand-transform effects/client-current-hand-transform)
(def client-notify-charge-coin-throw! effects/client-notify-charge-coin-throw!)

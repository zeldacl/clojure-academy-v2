(ns cn.li.mcmod.runtime.hooks.effects
  "Client effects/render hooks (delegates to hooks-core during migration)."
  (:require [cn.li.mcmod.runtime.hooks-core :as hooks-core]))

(def client-poll-particle-effects hooks-core/client-poll-particle-effects)
(def client-poll-sound-effects hooks-core/client-poll-sound-effects)
(def client-enqueue-level-effect! hooks-core/client-enqueue-level-effect!)
(def client-build-level-effect-plan hooks-core/client-build-level-effect-plan)
(def client-tick-level-effects! hooks-core/client-tick-level-effects!)
(def client-charge-coin-visual-state hooks-core/client-charge-coin-visual-state)
(def client-railgun-charge-visual-state hooks-core/client-railgun-charge-visual-state)
(def client-body-intensify-charge-visual-state hooks-core/client-body-intensify-charge-visual-state)
(def client-current-charging-visual-state hooks-core/client-current-charging-visual-state)
(def client-tick-hand-effects! hooks-core/client-tick-hand-effects!)
(def client-drain-camera-pitch-deltas! hooks-core/client-drain-camera-pitch-deltas!)
(def client-current-hand-transform hooks-core/client-current-hand-transform)
(def client-notify-charge-coin-throw! hooks-core/client-notify-charge-coin-throw!)

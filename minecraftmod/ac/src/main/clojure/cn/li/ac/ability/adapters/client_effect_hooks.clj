(ns cn.li.ac.ability.adapters.client-effect-hooks
  "Client FX/effect hook composition for AC ability platform bridge."
  (:require [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.hand-effects :as hand-effects]
            [cn.li.ac.ability.client.keybinds :as client-keybinds]
            [cn.li.ac.ability.client.level-effects :as level-effects]))

(defn runtime-client-effect-hooks
  []
  {:client-poll-particle-effects
   (fn []
     (client-particles/poll-particle-effects!))

   :client-poll-sound-effects
   (fn []
     (client-sounds/poll-sound-effects!))

   :client-enqueue-level-effect!
   (fn [effect-id payload]
     (level-effects/enqueue-level-effect! effect-id payload))

   :client-build-level-effect-plan
   (fn
     ([camera-pos hand-center-pos tick]
      (level-effects/build-level-effect-plan camera-pos hand-center-pos tick))
     ([camera-pos hand-center-pos tick frame-context]
      (level-effects/build-level-effect-plan camera-pos hand-center-pos tick frame-context)))

   :client-tick-level-effects!
   (fn []
     (level-effects/tick-level-effects!))

   :client-tick-keys!
   (fn [key-state-fn get-player-uuid-fn]
     (binding [cn.li.ac.ability.client.keybinds/*get-player-uuid-fn* get-player-uuid-fn]
       (client-keybinds/tick-keys! key-state-fn)))

   :client-tick-hand-effects!
   (fn []
     (hand-effects/tick-hand-effects!))

   :client-drain-camera-pitch-deltas!
   (fn []
     (hand-effects/drain-camera-pitch-deltas!))

   :client-current-hand-transform
   (fn []
     (hand-effects/current-hand-transform))})

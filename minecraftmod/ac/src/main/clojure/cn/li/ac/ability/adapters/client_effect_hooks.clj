(ns cn.li.ac.ability.adapters.client-effect-hooks
  "Client FX/effect hook composition for AC ability platform bridge."
  (:require [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.keybinds :as client-keybinds]
            [cn.li.ac.client.font-init :as font-init]))

(defn runtime-client-effect-hooks
  []
  {:client-poll-particle-effects
   (fn [owner]
     (client-particles/poll-particle-effects! owner))

   :client-poll-sound-effects
   (fn [owner]
     (client-sounds/poll-sound-effects! owner))

   :client-tick-start!
   (fn [get-player-uuid-fn]
     ;; START-of-tick vanilla-input suppression: must run before handleKeybinds
     ;; reads the KeyMappings — the END-phase call cannot stop skill-owned
     ;; movement keys because KeyboardHandler re-reads them from GLFW.
     (binding [cn.li.ac.ability.client.keybinds/*get-player-uuid-fn* get-player-uuid-fn]
       (client-keybinds/sync-vanilla-input-overrides!)))

   :client-font-init!
   (fn []
     ;; The content client-init hook (RegisterRenderers) can fire before the
     ;; platform bridge is installed — the platform calls this again after
     ;; its bridge merge so the MSDF fonts register with the real ops.
     (font-init/init-fonts!))

   :client-tick-keys!
   (fn [key-state-fn get-player-uuid-fn]
     (binding [cn.li.ac.ability.client.keybinds/*get-player-uuid-fn* get-player-uuid-fn]
       (client-keybinds/tick-keys! key-state-fn)))

   })

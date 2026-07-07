(ns cn.li.ac.ability.client.screens.preset-editor-reactive
  "Reactive Preset Editor — signal-driven skill preset management.
   Migration stub for preset_editor.clj (managed-screens system)."
  (:require [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.signal :as sig]))

(defn create-runtime []
  (let [r (rt/create-runtime)
        selected-preset (sig/signal-l 0)
        selected-skill (sig/signal-o nil)
        pending-changes (sig/signal-o {})]
    (rt/put-user-signal! r :selected-preset selected-preset)
    (rt/put-user-signal! r :selected-skill selected-skill)
    (rt/put-user-signal! r :pending-changes pending-changes)
    r))

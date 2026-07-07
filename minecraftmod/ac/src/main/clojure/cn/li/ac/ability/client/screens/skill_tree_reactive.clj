(ns cn.li.ac.ability.client.screens.skill-tree-reactive
  "Reactive Skill Tree — signal-driven skill graph navigation.
   Migration stub for skill_tree.clj (complex graph layout + learning logic)."
  (:require [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.signal :as sig]))

(defn create-runtime []
  (let [r (rt/create-runtime)
        zoom (sig/signal-d 1.0)
        pan-x (sig/signal-d 0.0)
        pan-y (sig/signal-d 0.0)]
    (rt/put-user-signal! r :zoom zoom)
    (rt/put-user-signal! r :pan-x pan-x)
    (rt/put-user-signal! r :pan-y pan-y)
    r))

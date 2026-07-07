(ns cn.li.ac.terminal.client.install-effect-reactive
  "Reactive Install Effect — signal-driven progress animation.
   Migration stub for install_effect.clj (installation progress overlay)."
  (:require [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.signal :as sig]
            [cn.li.mcmod.ui.anim :as anim]))

(defn create-runtime []
  (let [r (rt/create-runtime)
        progress (sig/signal-d 0.0)
        phase (sig/signal-o :idle)]
    (rt/put-user-signal! r :progress progress)
    (rt/put-user-signal! r :phase phase)
    r))

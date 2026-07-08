(ns cn.li.ac.tutorial.client.preview-reactive
  "Reactive Tutorial Preview — signal-driven ViewGroup navigation.
   Replaces find-widget + set-texture! + clone-widget pattern."
  (:require [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.signal :as sig]))

(defn create-runtime []
  (let [r (rt/create-runtime)
        current-group (sig/signal-l 0)
        current-view (sig/signal-l 0)
        recipe-widget (sig/signal-o nil)]
    (rt/put-user-signal! r :current-group current-group)
    (rt/put-user-signal! r :current-view current-view)
    (rt/put-user-signal! r :recipe-widget recipe-widget)
    r))

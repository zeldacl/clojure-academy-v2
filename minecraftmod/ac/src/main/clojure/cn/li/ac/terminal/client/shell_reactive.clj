(ns cn.li.ac.terminal.client.shell-reactive
  "Reactive Terminal Shell — signal-driven command input + history.
   Migration stub for shell.clj (interactive terminal with app dispatch)."
  (:require [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.signal :as sig]))

(defn create-runtime []
  (let [r (rt/create-runtime)
        input-line (sig/signal-o "")
        history (sig/signal-o [])
        current-app (sig/signal-o nil)]
    (rt/put-user-signal! r :input-line input-line)
    (rt/put-user-signal! r :history history)
    (rt/put-user-signal! r :current-app current-app)
    r))

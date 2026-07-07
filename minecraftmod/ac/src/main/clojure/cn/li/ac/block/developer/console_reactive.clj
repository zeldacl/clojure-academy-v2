(ns cn.li.ac.block.developer.console-reactive
  "Reactive Developer Console — signal-driven text console.
   Migration stub for console.clj (interactive command-line REPL)."
  (:require [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.core :as ui]
            [cn.li.mcmod.ui.signal :as sig]))

(defn create-console-runtime []
  (let [r (rt/create-runtime)
        history (sig/signal-o [])
        input-text (sig/signal-o "")]
    (rt/put-user-signal! r :history history)
    (rt/put-user-signal! r :input-text input-text)
    r))

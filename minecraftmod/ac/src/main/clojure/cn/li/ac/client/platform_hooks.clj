(ns cn.li.ac.client.platform-hooks
  "CLIENT-ONLY: register ac client actions with mcmod platform bridge."
  (:require [cn.li.ac.terminal.client.actions :as terminal-actions]
            [cn.li.ac.tutorial.client.state :as tutorial-state]
            [cn.li.mcmod.client.content-actions :as content-actions]))

(defn install-client-content-actions!
  []
  (content-actions/install-client-content-actions!
   {:toggle-terminal! terminal-actions/toggle-terminal!
    :tick-tutorial-background-sync! tutorial-state/tick-background-sync!}
   "ac-client-content-actions")
  nil)

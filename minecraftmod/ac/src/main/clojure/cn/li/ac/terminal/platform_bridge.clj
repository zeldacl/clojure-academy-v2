(ns cn.li.ac.terminal.platform-bridge
  "AC terminal bindings for the platform-neutral terminal UI bridge."
  (:require [cn.li.mcmod.platform.terminal-ui :as terminal-ui]
            [cn.li.ac.terminal.terminal-gui :as terminal-gui]
            [cn.li.mcmod.util.log :as log]))

(defonce ^:private hooks-installed? (atom false))

(defn install-terminal-ui-hooks!
  []
  (when (compare-and-set! hooks-installed? false true)
    (terminal-ui/register-terminal-ui-hooks!
      {:create-terminal-gui
       (fn [player]
         (terminal-gui/create-terminal-gui player))})
    (log/info "AC terminal UI hooks installed"))
  nil)
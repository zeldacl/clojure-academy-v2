(ns cn.li.ac.terminal.platform-bridge
  "AC terminal bindings for the platform-neutral terminal UI bridge."
  (:require [cn.li.mcmod.platform.terminal-ui :as terminal-ui]
            [cn.li.ac.terminal.terminal-gui :as terminal-gui]
            [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]
            [cn.li.mcmod.util.log :as log]))

(defonce-guard hooks-installed?)

(defn install-terminal-ui-hooks!
  []
  (with-init-guard hooks-installed?
    (terminal-ui/register-terminal-ui-hooks!
      {:create-terminal-gui
       (fn [player]
         (terminal-gui/create-terminal-gui player))})
    (log/info "AC terminal UI hooks installed"))
  nil)
(ns cn.li.ac.terminal.platform-bridge
  "AC terminal bindings for the platform-neutral UI widget bridge."
  (:require [cn.li.mcmod.platform.ui :as platform-ui]
            [cn.li.ac.terminal.terminal-gui :as terminal-gui]
            [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]
            [cn.li.mcmod.util.log :as log]))

(defonce-guard hooks-installed?)

(defn install-terminal-ui-hooks!
  []
  (with-init-guard hooks-installed?
    (platform-ui/register-widget-factory!
      :ac/terminal-gui
      (fn [{:keys [player]}]
        (terminal-gui/create-terminal-gui player)))
    (log/info "AC terminal UI hooks installed"))
  nil)
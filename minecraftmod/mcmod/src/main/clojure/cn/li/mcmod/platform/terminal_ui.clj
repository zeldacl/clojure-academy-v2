(ns cn.li.mcmod.platform.terminal-ui
  "Platform-neutral bridge for terminal GUI widget construction."
  (:require [cn.li.mcmod.util.log :as log]))

(defonce ^:private ui-hooks
  (atom {:create-terminal-gui (fn [_player] nil)}))

(defn register-terminal-ui-hooks!
  [hooks]
  (swap! ui-hooks merge hooks)
  nil)

(defn create-terminal-gui
  [player]
  (let [gui-widget ((:create-terminal-gui @ui-hooks) player)]
    (when-not gui-widget
      (log/warn "Terminal UI bridge returned nil GUI widget"))
    gui-widget))
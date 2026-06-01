(ns cn.li.ac.terminal.init
  "Terminal system initialization."
  (:require [cn.li.ac.registry.hooks :as hooks]
            [cn.li.ac.terminal.network :as network]
            [cn.li.mcmod.util.log :as log]))

(defn init-terminal!
  "Initialize the terminal system (network handlers only)."
  []
  (log/info "Initializing terminal system...")
  (hooks/register-network-handler! network/register-handlers!)
  (log/info "Terminal system initialized successfully"))

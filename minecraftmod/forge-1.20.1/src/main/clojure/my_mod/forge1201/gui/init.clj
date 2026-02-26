(ns my-mod.forge1201.gui.init
  "Forge 1.20.1 GUI System Initialization"
  (:require [my-mod.forge1201.gui.registry-impl :as registry-impl]
            [my-mod.forge1201.gui.network :as network]
            [my-mod.util.log :as log]))

(defn init-common!
  "Initialize common GUI system (server + client)"
  []
  (log/info "=== Initializing Forge 1.20.1 GUI System (Common) ===")
  (network/init!)
  (registry-impl/init!)
  (log/info "=== Forge 1.20.1 GUI System (Common) Initialized ==="))

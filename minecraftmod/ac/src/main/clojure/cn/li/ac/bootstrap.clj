(ns cn.li.ac.bootstrap
  "AC module initialization entry point.
   Called by Forge/Fabric after SPI is installed."
  (:require [cn.li.mcmod.util.log :as log]
            [cn.li.ac.input-ids :as input-ids]))

(defn initialize-keybindings!
  "Initialize the keybinding system for AC.
   
   Prerequisites:
   - mcmod SPI providers (key-scheme-provider, vanilla-input-control) must be installed
   - Platform has called this from mod.clj
   
   This function:
   1. Calls AC input_ids bootstrap to register all keybindings
   2. Can be extended in the future for other keybinding-related initialization"
  []
  (try
    (log/info "Initializing AC keybindings...")
    
    ;; Bootstrap keybindings (registers to mcmod protocol)
    (input-ids/bootstrap!)
    
    (log/info "AC keybindings initialization complete")
    nil
    
    (catch Exception e
      (log/error e "Failed to initialize AC keybindings")
      (throw e))))

(defn get-all-keybindings
  "Get all registered keybindings configuration for platform initialization.
   Used by Forge/Fabric to extract config and create platform-specific structures."
  []
  (try
    (input-ids/get-input-ids)
    (catch Exception e
      (log/error e "Failed to get AC keybindings configuration")
      nil)))

(ns cn.li.fabric1201.client-mod-init
  "Fabric 1.20.1 client-side mod entry point - handles ClientModInitializer interface"
  (:require [cn.li.fabric1201.client.init :as client-init]
            [cn.li.mcmod.util.log :as log])
  (:gen-class
  :name cn.li.fabric1201.FabricClientModInitBridge
   :implements [net.fabricmc.api.ClientModInitializer]
   :prefix "client-"))

(defn client-onInitializeClient
  "Fabric client initialization entry point
  
  Called when Fabric client is being initialized.
  Loads the client initialization module and invokes setup code."
  [_]
  (try
    (log/info "Initializing Fabric 1.20.1 client...")
    (client-init/init-client)
    (log/info "Fabric 1.20.1 client initialization complete")
    (catch Exception e
      (log/error "Failed to initialize Fabric client:" e))))

(ns my-mod.fabric1201.client-mod-init
  "Fabric 1.20.1 client-side mod entry point - handles ClientModInitializer interface"
  (:require [my-mod.fabric1201.client.init :as client-init]
            [my-mod.util.log :as log])
  (:import [net.fabricmc.api ClientModInitializer])
  (:gen-class
   :name com.example.my_mod1201.MyModFabricClient
   :implements [net.fabricmc.api.ClientModInitializer]
   :prefix "client-"))

;; ============================================================================
;; ClientModInitializer Implementation
;; ============================================================================

(defn client-onInitializeClient
  "Fabric client initialization entry point
  
  Called when Fabric client is being initialized.
  Loads the client initialization module and invokes setup code."
  [this]
  (try
    (log/info "Initializing Fabric 1.20.1 client...")
    ;; This will load and initialize all client-side systems
    ;; including renderer registration
    (client-init/init-client)
    (log/info "Fabric 1.20.1 client initialization complete")
    (catch Exception e
      (log/error "Failed to initialize Fabric client:" e))))

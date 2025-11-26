(ns my-mod.fabric1201.gui.screen-impl
  "Fabric 1.20.1 Client-side Screen Implementation
  
  This namespace handles Fabric-specific screen registration mechanics.
  Core screen creation logic is in my-mod.wireless.gui.screen-factory.
  
  Platform-agnostic design: Reads GUI metadata and loops through all GUIs
  to register, eliminating hardcoded game concepts (Node, Matrix, etc.)."
  (:require [my-mod.wireless.gui.screen-factory :as screen-factory]
            [my-mod.wireless.gui.gui-metadata :as gui-metadata]
            [my-mod.util.log :as log])
  (:import [net.minecraft.client.gui.screen.ingame HandledScreen]
           [net.minecraft.entity.player PlayerInventory]
           [net.minecraft.text Text]
           [net.fabricmc.fabric.api.client.screenhandler.v1 ScreenRegistry]))

;; ============================================================================
;; Screen Factory Registration (Fabric-specific)
;; ============================================================================

(defn register-screens!
  "Register screen factories with Fabric
  
  Platform-agnostic implementation: Loops through all GUI IDs from metadata
  and registers corresponding screen factories dynamically.
  
  No hardcoded game concepts - adding new GUIs requires only updating metadata.
  
  Should be called during client initialization"
  []
  (log/info "Registering GUI screens for Fabric 1.20.1")
  
  (try
    (let [platform :fabric-1.20.1]
      
      ;; Loop through all registered GUIs from metadata
      (doseq [gui-id (gui-metadata/get-all-gui-ids)]
        (let [handler-type (gui-metadata/get-menu-type platform gui-id)
              factory-fn-kw (gui-metadata/get-screen-factory-fn gui-id)]
          
          (when (and handler-type factory-fn-kw)
            ;; Get the actual factory function from screen-factory namespace
            (let [factory-fn (ns-resolve 'my-mod.wireless.gui.screen-factory factory-fn-kw)]
              (if factory-fn
                (do
                  (net.fabricmc.fabric.api.client.screenhandler.v1.ScreenRegistry/register
                    handler-type
                    (reify net.fabricmc.fabric.api.client.screenhandler.v1.ScreenRegistry$Factory
                      (create [_ handler player-inventory title]
                        (factory-fn handler player-inventory title))))
                  (log/info "Registered screen factory for GUI ID" gui-id))
                (log/warn "Screen factory function not found:" factory-fn-kw "for GUI ID" gui-id))))))
    
    (log/info "Screen factories registered successfully (Fabric)")
    
    (catch Exception e
      (log/error "Failed to register screen factories:" (.getMessage e))
      (.printStackTrace e))))

;; Alternative: Using HandledScreens (newer Fabric API)
(defn register-screens-alt!
  "Register screens using HandledScreens API (alternative method)
  
  Delegates to platform-agnostic screen-factory for actual screen creation."
  []
  (log/info "Registering Wireless GUI screens using HandledScreens API")
  
  (try
    ;; This is the newer Fabric API method
    (net.minecraft.client.gui.screen.ingame.HandledScreens/register
      @my_mod.fabric1201.gui.registry_impl/NODE_HANDLER_TYPE
      (reify java.util.function.Function
        (apply [_ handler-and-inventory]
          (let [handler (.getLeft handler-and-inventory)
                player-inventory (.getRight handler-and-inventory)]
            (screen-factory/create-node-screen handler player-inventory (Text/literal "Wireless Node"))))))
    
    (net.minecraft.client.gui.screen.ingame.HandledScreens/register
      @my_mod.fabric1201.gui.registry_impl/MATRIX_HANDLER_TYPE
      (reify java.util.function.Function
        (apply [_ handler-and-inventory]
          (let [handler (.getLeft handler-and-inventory)
                player-inventory (.getRight handler-and-inventory)]
            (screen-factory/create-matrix-screen handler player-inventory (Text/literal "Wireless Matrix"))))))
    
    (log/info "Screen factories registered successfully (HandledScreens)")
    
    (catch Exception e
      (log/warn "HandledScreens API not available, falling back to ScreenRegistry")
      (register-screens!))))

;; ============================================================================
;; Initialization
;; ============================================================================

(defn init-client!
  "Initialize client-side GUI system
  
  Should be called during client mod initialization"
  []
  (log/info "Initializing Fabric 1.20.1 client GUI system")
  
  ;; Try newer API first, fall back to legacy if needed
  (try
    (register-screens-alt!)
    (catch Exception e
      (log/warn "Using legacy ScreenRegistry API")
      (register-screens!)))
  
  (log/info "Fabric 1.20.1 client GUI system initialized"))

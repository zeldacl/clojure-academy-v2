(ns my-mod.forge1165.gui.screen-impl
  "Forge 1.16.5 Client-side Screen Implementation
  
  This namespace handles Forge-specific screen registration mechanics.
  Core screen creation logic is in my-mod.wireless.gui.screen-factory.
  
  Platform-agnostic design: Reads GUI metadata and loops through all GUIs
  to register, eliminating hardcoded game concepts (Node, Matrix, etc.)."
  (:require [my-mod.gui.platform-adapter :as gui]
            [my-mod.util.log :as log])
  (:import [net.minecraft.client.gui.screen.inventory ContainerScreen]
           [net.minecraft.entity.player PlayerInventory]
           [net.minecraft.util.text ITextComponent]))

;; ============================================================================
;; Screen Factory Registration (Forge-specific)
;; ============================================================================

(defn register-screens!
  "Register screen factories with Minecraft
  
  Platform-agnostic implementation: Loops through all GUI IDs from metadata
  and registers corresponding screen factories dynamically.
  
  No hardcoded game concepts - adding new GUIs requires only updating metadata.
  
  Should be called during client initialization (FMLClientSetupEvent)"
  []
  (log/info "Registering GUI screens for Forge 1.16.5")
  
  (try
    (let [screen-manager net.minecraft.client.gui.ScreenManager
          platform :forge-1.16.5]
      
      ;; Loop through all registered GUIs from metadata
      (doseq [gui-id (gui/get-all-gui-ids)]
        (let [menu-type (gui/get-menu-type platform gui-id)
              factory-fn-kw (gui/get-screen-factory-fn-kw gui-id)]
          
          (when (and menu-type factory-fn-kw)
            ;; Get the actual factory function from screen-factory namespace
            (let [factory-fn (ns-resolve 'my-mod.gui.platform-adapter factory-fn-kw)]
              (if factory-fn
                (do
                  (.registerFactory screen-manager
                                   menu-type
                                   (reify net.minecraft.client.gui.IScreenFactory
                                     (create [_ container player-inventory title]
                                       (factory-fn container player-inventory title))))
                  (log/info \"Registered screen factory for GUI ID\" gui-id))
                (log/warn \"Screen factory function not found:\" factory-fn-kw \"for GUI ID\" gui-id))))))
      
      (log/info "Screen factories registered successfully"))
    
    (catch Exception e
      (log/error "Failed to register screen factories:" (.getMessage e))
      (.printStackTrace e))))

;; ============================================================================
;; Initialization
;; ============================================================================

(defn init-client!
  "Initialize client-side GUI system
  
  Should be called during FMLClientSetupEvent"
  []
  (log/info "Initializing Forge 1.16.5 client GUI system")
  
  ;; Register screen factories
  (register-screens!)
  
  (log/info "Forge 1.16.5 client GUI system initialized"))

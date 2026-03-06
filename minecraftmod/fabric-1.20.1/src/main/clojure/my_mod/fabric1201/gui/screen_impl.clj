(ns my-mod.fabric1201.gui.screen-impl
  "Fabric 1.20.1 Client-side Screen Implementation
  
  Platform-agnostic design: Reads GUI metadata and loops through all GUIs
  to register, eliminating hardcoded game concepts."
  (:require [my-mod.gui.platform-adapter :as gui]
            [my-mod.util.log :as log])
  (:import [net.minecraft.client.gui.screens MenuScreens]
           [net.minecraft.network.chat Component]))

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
      (doseq [gui-id (gui/get-all-gui-ids)]
        (let [menu-type (gui/get-menu-type platform gui-id)
              factory-fn-kw (gui/get-screen-factory-fn-kw gui-id)]
          
          (when (and menu-type factory-fn-kw)
            ;; Get the actual factory function from screen-factory namespace
            (let [factory-fn (ns-resolve 'my-mod.gui.platform-adapter factory-fn-kw)]
              (if factory-fn
                (do
                  (MenuScreens/register
                    menu-type
                    (reify net.minecraft.client.gui.screens.MenuScreens$ScreenConstructor
                      (create [_ menu player-inventory title]
                        (factory-fn menu player-inventory title))))
                  (log/info "Registered screen factory for GUI ID" gui-id))
                (log/warn "Screen factory function not found:" factory-fn-kw "for GUI ID" gui-id))))))
    
    (log/info "Screen factories registered successfully (Fabric)")
    
    (catch Exception e
      (log/error "Failed to register screen factories:" (.getMessage e))
      (.printStackTrace e))))

;; Alternative: Using HandledScreens (newer Fabric API)
;; Alternative: Using HandledScreens (newer Fabric API)
;; (register-screens-alt!) was Yarn-era; MenuScreens works with official mappings.

;; ============================================================================
;; Initialization
;; ============================================================================

(defn init-client!
  "Initialize client-side GUI system
  
  Should be called during client mod initialization"
  []
  (log/info "Initializing Fabric 1.20.1 client GUI system")
  (register-screens!)
  
  (log/info "Fabric 1.20.1 client GUI system initialized"))

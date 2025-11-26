(ns my-mod.forge1165.gui.screen-impl
  "Forge 1.16.5 Client-side Screen Implementation
  
  This namespace handles Forge-specific screen registration mechanics.
  Core screen creation logic is in my-mod.wireless.gui.screen-factory."
  (:require [my-mod.wireless.gui.screen-factory :as screen-factory]
            [my-mod.util.log :as log])
  (:import [net.minecraft.client.gui.screen.inventory ContainerScreen]
           [net.minecraft.entity.player PlayerInventory]
           [net.minecraft.util.text ITextComponent]))

;; ============================================================================
;; Screen Factory Registration (Forge-specific)
;; ============================================================================

(defn register-screens!
  "Register screen factories with Minecraft
  
  Delegates to platform-agnostic screen-factory for actual screen creation.
  This function only handles Forge-specific registration mechanics.
  
  Should be called during client initialization (FMLClientSetupEvent)"
  []
  (log/info "Registering Wireless GUI screens for Forge 1.16.5")
  
  ;; Use ScreenManager to register screen factories
  (try
    (let [screen-manager net.minecraft.client.gui.ScreenManager]
      
      ;; Register Node screen
      (.registerFactory screen-manager
                       @my_mod.forge1165.gui.registry_impl/NODE_MENU_TYPE
                       (reify net.minecraft.client.gui.IScreenFactory
                         (create [_ container player-inventory title]
                           (screen-factory/create-node-screen container player-inventory title))))
      
      ;; Register Matrix screen
      (.registerFactory screen-manager
                       @my_mod.forge1165.gui.registry_impl/MATRIX_MENU_TYPE
                       (reify net.minecraft.client.gui.IScreenFactory
                         (create [_ container player-inventory title]
                           (screen-factory/create-matrix-screen container player-inventory title))))
      
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

(ns my-mod.forge1165.gui.screen-impl
  "Forge 1.16.5 Client-side Screen Implementation"
  (:require [my-mod.wireless.gui.node-gui :as node-gui]
            [my-mod.wireless.gui.matrix-gui :as matrix-gui]
            [my-mod.util.log :as log])
  (:import [net.minecraft.client.gui.screen.inventory ContainerScreen]
           [net.minecraft.entity.player PlayerInventory]
           [net.minecraft.util.text ITextComponent]))

;; ============================================================================
;; Screen Registration (Called by Forge on client side)
;; ============================================================================

(defn create-node-screen
  "Create Node GUI screen
  
  Args:
  - container: Java Container instance
  - player-inventory: PlayerInventory
  - title: ITextComponent
  
  Returns: ContainerScreen instance"
  [container player-inventory title]
  (log/info "Creating Node screen")
  
  (try
    (let [;; Extract Clojure container from Java wrapper
          clj-container (.getClojureContainer container)
          
          ;; Create CGui screen
          cgui-screen (node-gui/create-screen clj-container container)]
      
      (log/info "Node screen created successfully")
      cgui-screen)
    
    (catch Exception e
      (log/error "Failed to create Node screen:" (.getMessage e))
      (.printStackTrace e)
      nil)))

(defn create-matrix-screen
  "Create Matrix GUI screen"
  [container player-inventory title]
  (log/info "Creating Matrix screen")
  
  (try
    (let [clj-container (.getClojureContainer container)
          cgui-screen (matrix-gui/create-screen clj-container container)]
      
      (log/info "Matrix screen created successfully")
      cgui-screen)
    
    (catch Exception e
      (log/error "Failed to create Matrix screen:" (.getMessage e))
      (.printStackTrace e)
      nil)))

;; ============================================================================
;; Screen Factory Registration
;; ============================================================================

(defn register-screens!
  "Register screen factories with Minecraft
  
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
                           (create-node-screen container player-inventory title))))
      
      ;; Register Matrix screen
      (.registerFactory screen-manager
                       @my_mod.forge1165.gui.registry_impl/MATRIX_MENU_TYPE
                       (reify net.minecraft.client.gui.IScreenFactory
                         (create [_ container player-inventory title]
                           (create-matrix-screen container player-inventory title))))
      
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

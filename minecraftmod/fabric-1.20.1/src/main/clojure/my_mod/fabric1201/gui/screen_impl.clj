(ns my-mod.fabric1201.gui.screen-impl
  "Fabric 1.20.1 Client-side Screen Implementation"
  (:require [my-mod.wireless.gui.node-gui :as node-gui]
            [my-mod.wireless.gui.matrix-gui :as matrix-gui]
            [my-mod.util.log :as log])
  (:import [net.minecraft.client.gui.screen.ingame HandledScreen]
           [net.minecraft.entity.player PlayerInventory]
           [net.minecraft.text Text]
           [net.fabricmc.fabric.api.client.screenhandler.v1 ScreenRegistry]))

;; ============================================================================
;; Screen Creation (Called by Fabric on client side)
;; ============================================================================

(defn create-node-screen
  "Create Node GUI screen
  
  Args:
  - handler: ScreenHandler instance
  - player-inventory: PlayerInventory
  - title: Text
  
  Returns: HandledScreen instance"
  [handler player-inventory title]
  (log/info "Creating Node screen (Fabric)")
  
  (try
    (let [;; Extract Clojure container from handler
          clj-container (.getClojureContainer handler)
          
          ;; Create CGui screen
          cgui-screen (node-gui/create-screen clj-container handler)]
      
      (log/info "Node screen created successfully")
      cgui-screen)
    
    (catch Exception e
      (log/error "Failed to create Node screen:" (.getMessage e))
      (.printStackTrace e)
      nil)))

(defn create-matrix-screen
  "Create Matrix GUI screen"
  [handler player-inventory title]
  (log/info "Creating Matrix screen (Fabric)")
  
  (try
    (let [clj-container (.getClojureContainer handler)
          cgui-screen (matrix-gui/create-screen clj-container handler)]
      
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
  "Register screen factories with Fabric
  
  Should be called during client initialization"
  []
  (log/info "Registering Wireless GUI screens for Fabric 1.20.1")
  
  ;; Use Fabric's ScreenRegistry (or HandledScreens in newer versions)
  (try
    ;; Register Node screen
    (net.fabricmc.fabric.api.client.screenhandler.v1.ScreenRegistry/register
      @my_mod.fabric1201.gui.registry_impl/NODE_HANDLER_TYPE
      (reify net.fabricmc.fabric.api.client.screenhandler.v1.ScreenRegistry$Factory
        (create [_ handler player-inventory title]
          (create-node-screen handler player-inventory title))))
    
    ;; Register Matrix screen
    (net.fabricmc.fabric.api.client.screenhandler.v1.ScreenRegistry/register
      @my_mod.fabric1201.gui.registry_impl/MATRIX_HANDLER_TYPE
      (reify net.fabricmc.fabric.api.client.screenhandler.v1.ScreenRegistry$Factory
        (create [_ handler player-inventory title]
          (create-matrix-screen handler player-inventory title))))
    
    (log/info "Screen factories registered successfully (Fabric)")
    
    (catch Exception e
      (log/error "Failed to register screen factories:" (.getMessage e))
      (.printStackTrace e))))

;; Alternative: Using HandledScreens (newer Fabric API)
(defn register-screens-alt!
  "Register screens using HandledScreens API (alternative method)"
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
            (create-node-screen handler player-inventory (Text/literal "Wireless Node"))))))
    
    (net.minecraft.client.gui.screen.ingame.HandledScreens/register
      @my_mod.fabric1201.gui.registry_impl/MATRIX_HANDLER_TYPE
      (reify java.util.function.Function
        (apply [_ handler-and-inventory]
          (let [handler (.getLeft handler-and-inventory)
                player-inventory (.getRight handler-and-inventory)]
            (create-matrix-screen handler player-inventory (Text/literal "Wireless Matrix"))))))
    
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

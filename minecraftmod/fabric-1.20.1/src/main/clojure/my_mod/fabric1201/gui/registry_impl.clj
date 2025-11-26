(ns my-mod.fabric1201.gui.registry-impl
  "Fabric 1.20.1 GUI Registration Implementation"
  (:require [my-mod.wireless.gui.registry :as gui-registry]
            [my-mod.fabric1201.gui.bridge :as bridge]
            [my-mod.util.log :as log])
  (:import [net.minecraft.screen ScreenHandlerType]
           [net.minecraft.util Identifier]
           [net.minecraft.registry Registry Registries]
           [net.fabricmc.fabric.api.screenhandler.v1 ScreenHandlerRegistry]))

;; ============================================================================
;; ScreenHandlerType Registry
;; ============================================================================

(defonce node-handler-type (atom nil))
(defonce matrix-handler-type (atom nil))

(defn create-screen-handler-type
  "Create a ScreenHandlerType for a GUI
  
  Args:
  - gui-id: int
  
  Returns: ScreenHandlerType instance"
  [gui-id]
  ;; Use ScreenHandlerRegistry.SimpleClientHandlerFactory
  (ScreenHandlerRegistry/registerSimple
    (Identifier. "my_mod" 
                (case gui-id
                  0 "wireless_node_gui"
                  1 "wireless_matrix_gui"
                  "unknown_gui"))
    (reify net.fabricmc.fabric.api.screenhandler.v1.ScreenHandlerRegistry$SimpleClientHandlerFactory
      (create [_ sync-id player-inventory]
        (let [handler (gui-registry/get-gui-handler)
              player (.player player-inventory)
              world (.getWorld player)
              pos (.getBlockPos player)
              clj-container (.get-server-container handler gui-id player world pos)]
          (if clj-container
            (bridge/wrap-clojure-container 
              sync-id 
              (case gui-id
                0 @node-handler-type
                1 @matrix-handler-type
                nil)
              clj-container)
            (do
              (log/error "Failed to create container for GUI" gui-id)
              nil)))))))

(defn create-extended-screen-handler-type
  "Create an Extended ScreenHandlerType (with packet data)
  
  Args:
  - gui-id: int
  
  Returns: ScreenHandlerType instance"
  [gui-id]
  ;; Use ScreenHandlerRegistry.ExtendedClientHandlerFactory
  (ScreenHandlerRegistry/registerExtended
    (Identifier. "my_mod"
                (case gui-id
                  0 "wireless_node_gui"
                  1 "wireless_matrix_gui"
                  "unknown_gui"))
    (reify net.fabricmc.fabric.api.screenhandler.v1.ScreenHandlerRegistry$ExtendedClientHandlerFactory
      (create [_ sync-id player-inventory buf]
        ;; Read GUI ID from buffer
        (let [gui-id-from-buf (.readInt buf)
              has-tile (.readBoolean buf)
              pos (when has-tile (.readBlockPos buf))
              handler (gui-registry/get-gui-handler)
              player (.player player-inventory)
              world (.getWorld player)
              clj-container (.get-server-container handler gui-id-from-buf player world pos)]
          (if clj-container
            (bridge/wrap-clojure-container
              sync-id
              (case gui-id-from-buf
                0 @node-handler-type
                1 @matrix-handler-type
                nil)
              clj-container)
            (do
              (log/error "Failed to create container for GUI" gui-id-from-buf)
              nil)))))))

(defn register-screen-handler-types!
  "Register all screen handler types with Fabric registry"
  []
  (log/info "Registering Wireless GUI screen handler types for Fabric 1.20.1")
  
  ;; Create and register handler types using Fabric API
  (reset! node-handler-type 
          (create-extended-screen-handler-type gui-registry/gui-wireless-node))
  (reset! matrix-handler-type 
          (create-extended-screen-handler-type gui-registry/gui-wireless-matrix))
  
  (log/info "Registered screen handler types: wireless_node_gui, wireless_matrix_gui"))

;; ============================================================================
;; GUI Opening
;; ============================================================================

(defn open-gui-for-player
  "Open GUI for player using Fabric API
  
  Args:
  - player: ServerPlayerEntity
  - gui-id: int
  - tile-entity: TileEntity (optional, can be nil)
  
  This uses Fabric's openHandledScreen"
  [player gui-id tile-entity]
  (log/info "Opening GUI" gui-id "for player" (.getName player))
  
  (try
    ;; Create screen handler factory
    (let [factory (bridge/create-extended-screen-handler-factory gui-id tile-entity)]
      
      ;; Open GUI using Fabric API
      (.openHandledScreen player factory)
      
      (log/info "GUI opened successfully"))
    
    (catch Exception e
      (log/error "Failed to open GUI:" (.getMessage e))
      (.printStackTrace e))))

;; ============================================================================
;; Registry Implementation
;; ============================================================================

(defmethod gui-registry/register-gui-handler :fabric-1.20.1 [_]
  (log/info "Registering GUI handler for Fabric 1.20.1")
  
  ;; Register screen handler types
  (register-screen-handler-types!)
  
  (log/info "Fabric 1.20.1 GUI handler registered"))

;; ============================================================================
;; Platform-Specific GUI Opening
;; ============================================================================

(defn open-node-gui-fabric
  "Open Node GUI (Fabric 1.20.1 specific)
  
  Args:
  - player: ServerPlayerEntity
  - world: World
  - pos: BlockPos"
  [player world pos]
  (let [tile-entity (.getBlockEntity world pos)]
    (open-gui-for-player player gui-registry/gui-wireless-node tile-entity)))

(defn open-matrix-gui-fabric
  "Open Matrix GUI (Fabric 1.20.1 specific)"
  [player world pos]
  (let [tile-entity (.getBlockEntity world pos)]
    (open-gui-for-player player gui-registry/gui-wireless-matrix tile-entity)))

;; ============================================================================
;; Initialization
;; ============================================================================

(defn init!
  "Initialize Fabric 1.20.1 GUI system
  
  Should be called during mod initialization"
  []
  (log/info "Initializing Fabric 1.20.1 GUI system")
  
  ;; Register with platform-agnostic registry
  (gui-registry/register-gui-handler :fabric-1.20.1)
  
  (log/info "Fabric 1.20.1 GUI system initialized"))

;; Export handler types for bridge access
(def NODE_HANDLER_TYPE node-handler-type)
(def MATRIX_HANDLER_TYPE matrix-handler-type)

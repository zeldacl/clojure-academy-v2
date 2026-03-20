(ns cn.li.fabric1201.gui.registry-impl
  "Fabric 1.20.1 GUI Registration Implementation"
  (:require [cn.li.ac.gui.platform-adapter :as gui]
            [cn.li.fabric1201.gui.bridge :as bridge]
            [cn.li.ac.wireless.gui.registry :as gui-registry]
            [cn.li.ac.wireless.gui.gui-metadata :as gui-meta]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.world.inventory MenuType]
           [net.minecraft.resources ResourceLocation]
           [net.fabricmc.fabric.api.screenhandler.v1 ScreenHandlerRegistry]))

;; ============================================================================
;; ScreenHandlerType Registry
;; ============================================================================

(defonce gui-handler-types
  ^{:doc "Map from GUI ID to registered MenuType instances

  Platform-agnostic design: Uses GUI IDs instead of game-specific names.
  Structure: {gui-id MenuType, ...}"}
  (atom {}))

(defn get-handler-type
  "Get registered MenuType for a GUI ID
  
  Args:
  - gui-id: int
  
  Returns: MenuType or nil"
  [gui-id]
  (get @gui-handler-types gui-id))

(defn create-screen-handler-type
  "Create a MenuType for a GUI
  
  Platform-agnostic design: Uses gui-metadata for registry names.
  
  Args:
  - gui-id: int
  
  Returns: MenuType instance"
  [gui-id]
  (let [registry-name (gui/get-registry-name gui-id)]
    ;; Use ScreenHandlerRegistry.SimpleClientHandlerFactory
    (ScreenHandlerRegistry/registerSimple
      (ResourceLocation. modid/MOD-ID registry-name)
      (reify net.fabricmc.fabric.api.screenhandler.v1.ScreenHandlerRegistry$SimpleClientHandlerFactory
        (create [_ sync-id player-inventory]
          (let [handler (gui/get-gui-handler)
                player (.player player-inventory)
                world (.level player)
                pos (.blockPosition player)
                clj-container (.get-server-container handler gui-id player world pos)]
            (if clj-container
              (bridge/wrap-clojure-container sync-id (get-handler-type gui-id) clj-container)
              (do
                (log/error "Failed to create container for GUI" gui-id)
                nil))))))))

(defn create-extended-screen-handler-type
  "Create an Extended ScreenHandlerType (with packet data)
  
  Platform-agnostic design: Uses gui-metadata for registry names.
  
  Args:
  - gui-id: int
  
  Returns: MenuType instance"
  [gui-id]
  (let [registry-name (gui/get-registry-name gui-id)]
    ;; Use ScreenHandlerRegistry.ExtendedClientHandlerFactory
    (ScreenHandlerRegistry/registerExtended
      (ResourceLocation. modid/MOD-ID registry-name)
      (reify net.fabricmc.fabric.api.screenhandler.v1.ScreenHandlerRegistry$ExtendedClientHandlerFactory
        (create [_ sync-id player-inventory buf]
          ;; Read GUI ID from buffer
          (let [gui-id-from-buf (.readInt buf)
                has-tile (.readBoolean buf)
                pos (when has-tile (.readBlockPos buf))
                handler (gui-registry/get-gui-handler)
                player (.player player-inventory)
                world (.level player)
                clj-container (.get-server-container handler gui-id-from-buf player world pos)]
            (if clj-container
              (bridge/wrap-clojure-container sync-id (get-handler-type gui-id-from-buf) clj-container)
              (do
                (log/error "Failed to create container for GUI" gui-id-from-buf)
                nil))))))))

(defn register-screen-handler-types!
  "Register all screen handler types with Fabric registry
  
  Platform-agnostic design: Dynamically registers all GUI IDs from metadata."
  []
  (log/info "Registering GUI screen handler types for Fabric 1.20.1")
  
  ;; Create and register handler types for all GUI IDs
  (doseq [gui-id (gui/get-all-gui-ids)]
    (let [handler-type (create-extended-screen-handler-type gui-id)
          registry-name (gui/get-registry-name gui-id)]
      
      ;; Store in our map
      (swap! gui-handler-types assoc gui-id handler-type)
      (gui-meta/register-menu-type! :fabric-1.20.1 gui-id handler-type)
      
      (log/info "Registered screen handler type:" registry-name "for GUI ID" gui-id)))
  
  (log/info "Registered" (count @gui-handler-types) "screen handler types"))

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
      (try
        (clojure.lang.Reflector/invokeInstanceMethod player "openHandledScreen" (object-array [factory]))
        (catch Exception _
          ;; Fallback: some environments expose openMenu instead
          (clojure.lang.Reflector/invokeInstanceMethod player "openMenu" (object-array [factory]))))
      
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

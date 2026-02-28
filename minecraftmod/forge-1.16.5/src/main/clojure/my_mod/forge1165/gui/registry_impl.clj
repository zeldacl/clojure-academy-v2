(ns my-mod.forge1165.gui.registry-impl
  "Forge 1.16.5 GUI Registration Implementation"
  (:require [my-mod.gui.platform-adapter :as gui]
            [my-mod.forge1165.gui.bridge :as bridge]
            [my-mod.util.log :as log])
  (:import [net.minecraftforge.fml.network NetworkHooks]
           [net.minecraft.inventory.container ContainerType]
           [net.minecraftforge.registries ForgeRegistries]
           [net.minecraft.util ResourceLocation]))

;; ============================================================================
;; MenuType Registry
;; ============================================================================

(defonce gui-menu-types
  ^{:doc "Map from GUI ID to registered MenuType instances

  Platform-agnostic design: Uses GUI IDs instead of game-specific names.
  Structure: {gui-id ContainerType, ...}"}
  (atom {}))

(defn get-menu-type
  "Get registered MenuType for a GUI ID
  
  Args:
  - gui-id: int
  
  Returns: ContainerType or nil"
  [gui-id]
  (get @gui-menu-types gui-id))

(defn create-menu-type
  "Create a ContainerType for a GUI
  
  Args:
  - gui-id: int
  
  Returns: ContainerType instance"
  [gui-id]
  (ContainerType.
    (reify java.util.function.BiFunction
      (apply [_ window-id player-inventory]
        ;; This creates a dummy container - actual creation happens in Provider
        (let [handler (gui/get-gui-handler)
              player (.player player-inventory)
              world (.getWorld player)
              pos (.getPosition player)
              clj-container (.get-server-container handler gui-id player world pos)]
          (if clj-container
            (bridge/wrap-clojure-container window-id (get-menu-type gui-id) clj-container)
            (do
              (log/error "Failed to create container for GUI" gui-id)
              nil)))))))

(defn register-menu-types!
  "Register all menu types with Forge registry
  
  Platform-agnostic design: Dynamically registers all GUI IDs from metadata."
  []
  (log/info "Registering GUI menu types for Forge 1.16.5")
  
  ;; Create and register menu types for all GUI IDs
  (doseq [gui-id (gui/get-all-gui-ids)]
    (let [menu-type (create-menu-type gui-id)
          registry-name (gui/get-registry-name gui-id)
          resource-loc (ResourceLocation. modid/MOD-ID registry-name)]
      
      ;; Store in our map
      (swap! gui-menu-types assoc gui-id menu-type)
      
      ;; Register with Forge
      (.setRegistryName menu-type resource-loc)
      (.register ForgeRegistries/CONTAINERS resource-loc menu-type)
      
      (log/info "Registered menu type:" registry-name "for GUI ID" gui-id)))
  
  (log/info "Registered" (count @gui-menu-types) "menu types"))

;; ============================================================================
;; GUI Opening
;; ============================================================================

(defn open-gui-for-player
  "Open GUI for player using NetworkHooks
  
  Args:
  - player: ServerPlayerEntity
  - gui-id: int
  - tile-entity: TileEntity (optional, can be nil)
  
  This uses Forge's NetworkHooks to open the GUI properly"
  [player gui-id tile-entity]
  (log/info "Opening GUI" gui-id "for player" (.getName player))
  
  (try
    ;; Create container provider
    (let [provider (bridge/create-container-provider gui-id tile-entity)]
      
      ;; Open GUI using NetworkHooks
      (NetworkHooks/openGui player provider)
      
      (log/info "GUI opened successfully"))
    
    (catch Exception e
      (log/error "Failed to open GUI:" (.getMessage e))
      (.printStackTrace e))))

;; ============================================================================
;; Registry Implementation
;; ============================================================================

(defmethod gui-registry/register-gui-handler :forge-1.16.5 [_]
  (log/info "Registering GUI handler for Forge 1.16.5")
  
  ;; Register menu types
  (register-menu-types!)
  
  ;; Store menu types in a way the bridge can access them
  ;; (Uses gen-class static fields)
  (log/info "Forge 1.16.5 GUI handler registered"))

;; ============================================================================
;; Initialization
;; ============================================================================

(defn init!
  "Initialize Forge 1.16.5 GUI system
  
  Should be called during mod initialization"
  []
  (log/info "Initializing Forge 1.16.5 GUI system")
  
  ;; Register with platform-agnostic registry
  (gui-registry/register-gui-handler :forge-1.16.5)
  
  (log/info "Forge 1.16.5 GUI system initialized"))

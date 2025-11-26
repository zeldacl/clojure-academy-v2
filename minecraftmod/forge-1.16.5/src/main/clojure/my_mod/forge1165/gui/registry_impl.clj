(ns my-mod.forge1165.gui.registry-impl
  "Forge 1.16.5 GUI Registration Implementation"
  (:require [my-mod.wireless.gui.registry :as gui-registry]
            [my-mod.forge1165.gui.bridge :as bridge]
            [my-mod.util.log :as log])
  (:import [net.minecraftforge.fml.network NetworkHooks]
           [net.minecraft.inventory.container ContainerType]
           [net.minecraftforge.registries ForgeRegistries]
           [net.minecraft.util ResourceLocation]))

;; ============================================================================
;; MenuType Registry
;; ============================================================================

(defonce node-menu-type (atom nil))
(defonce matrix-menu-type (atom nil))

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
        (let [handler (gui-registry/get-gui-handler)
              player (.player player-inventory)
              world (.getWorld player)
              pos (.getPosition player)
              clj-container (.get-server-container handler gui-id player world pos)]
          (if clj-container
            (bridge/wrap-clojure-container window-id @node-menu-type clj-container)
            (do
              (log/error "Failed to create container for GUI" gui-id)
              nil)))))))

(defn register-menu-types!
  "Register all menu types with Forge registry"
  []
  (log/info "Registering Wireless GUI menu types for Forge 1.16.5")
  
  ;; Create menu types
  (reset! node-menu-type (create-menu-type gui-registry/gui-wireless-node))
  (reset! matrix-menu-type (create-menu-type gui-registry/gui-wireless-matrix))
  
  ;; Register with Forge
  (let [node-loc (ResourceLocation. "my_mod" "wireless_node_gui")
        matrix-loc (ResourceLocation. "my_mod" "wireless_matrix_gui")]
    
    (.setRegistryName @node-menu-type node-loc)
    (.setRegistryName @matrix-menu-type matrix-loc)
    
    (.register ForgeRegistries/CONTAINERS node-loc @node-menu-type)
    (.register ForgeRegistries/CONTAINERS matrix-loc @matrix-menu-type)
    
    (log/info "Registered menu types: wireless_node_gui, wireless_matrix_gui")))

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
;; Platform-Specific GUI Opening
;; ============================================================================

(defn open-node-gui-forge
  "Open Node GUI (Forge 1.16.5 specific)
  
  Args:
  - player: ServerPlayerEntity
  - world: World
  - pos: BlockPos"
  [player world pos]
  (let [tile-entity (.getTileEntity world pos)]
    (open-gui-for-player player gui-registry/gui-wireless-node tile-entity)))

(defn open-matrix-gui-forge
  "Open Matrix GUI (Forge 1.16.5 specific)"
  [player world pos]
  (let [tile-entity (.getTileEntity world pos)]
    (open-gui-for-player player gui-registry/gui-wireless-matrix tile-entity)))

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

;; Export menu types for Java bridge access
(def NODE_MENU_TYPE node-menu-type)
(def MATRIX_MENU_TYPE matrix-menu-type)

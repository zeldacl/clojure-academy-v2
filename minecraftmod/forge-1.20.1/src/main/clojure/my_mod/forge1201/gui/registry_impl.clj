(ns my-mod.forge1201.gui.registry-impl
  "Forge 1.20.1 GUI Registration Implementation
  
  Platform-agnostic design: Uses metadata-driven approach."
  (:require [my-mod.gui.platform-adapter :as gui]
            [my-mod.forge1201.gui.bridge :as bridge]
            [my-mod.wireless.gui.registry :as gui-registry]
            [my-mod.wireless.gui.gui-metadata :as gui-meta]
            [my-mod.config.modid :as modid]
            [my-mod.util.log :as log])
  (:import [net.minecraftforge.network NetworkHooks]
           [net.minecraftforge.common.extensions IForgeMenuType]
           [net.minecraftforge.network.IContainerFactory]
           [net.minecraft.network FriendlyByteBuf]
           [net.minecraft.world.inventory MenuType]
           [net.minecraft.core.registries BuiltInRegistries]
           [net.minecraft.core Registry]
           [net.minecraft.resources ResourceLocation]))

;; ============================================================================
;; MenuType Registry
;; ============================================================================

(defonce gui-menu-types
  ^{:doc "Map from GUI ID to registered MenuType instances

  Platform-agnostic design: Uses GUI IDs instead of game-specific names.
  Structure: {gui-id MenuType, ...}"}
  (atom {}))

(defn get-menu-type
  "Get registered MenuType for a GUI ID
  
  Args:
  - gui-id: int
  
  Returns: MenuType or nil"
  [gui-id]
  (get @gui-menu-types gui-id))

(defn create-menu-type
  "Create a MenuType for a GUI
  
  Args:
  - gui-id: int
  
  Returns: MenuType instance"
  [gui-id]
  (IForgeMenuType/create
    (reify IContainerFactory
      (create [_ window-id player-inventory ^FriendlyByteBuf buf]
        (let [handler (gui/get-gui-handler)
              player (.player player-inventory)
              world (.level player)
              pos (.readBlockPos buf)
              clj-container (.get-server-container handler gui-id player world pos)]
          (if clj-container
            (bridge/wrap-clojure-container window-id (get-menu-type gui-id) clj-container)
            (do (log/error "Failed to create container for GUI" gui-id) nil)))))))

(defn register-menu-types!
  "Register all menu types with Forge registry
  
  Platform-agnostic design: Dynamically registers all GUI IDs from metadata."
  []
  (log/info "Registering GUI menu types for Forge 1.20.1")
  
  ;; Create and register menu types for all GUI IDs
  (doseq [gui-id (gui/get-all-gui-ids)]
    (let [menu-type (create-menu-type gui-id)
          registry-name (gui/get-registry-name gui-id)
          resource-loc (ResourceLocation. modid/MOD-ID registry-name)]
      
      ;; Store in our map
      (swap! gui-menu-types assoc gui-id menu-type)
      (gui-meta/register-menu-type! :forge-1.20.1 gui-id menu-type)
      
      ;; Register with vanilla registry (Forge 1.20.1 uses vanilla registries)
      (Registry/register BuiltInRegistries/MENU resource-loc menu-type)
      
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
  - tile-entity: TileEntity (optional, can be nil)"
  [player gui-id tile-entity]
  (log/info "Opening GUI" gui-id "for player" (.getName player))
  (try
    (let [provider (bridge/create-menu-provider gui-id tile-entity)
          pos (when tile-entity
                (try
                  (if (map? tile-entity)
                    (:pos tile-entity)
                    (clojure.lang.Reflector/invokeInstanceMethod tile-entity "getBlockPos" (object-array [])))
                  (catch Exception _ nil)))]
      (if pos
        (NetworkHooks/openScreen
          player
          provider
          (reify java.util.function.Consumer
            (accept [_ buf]
              (.writeBlockPos buf pos))))
        (NetworkHooks/openScreen player provider))
      (log/info "GUI opened successfully"))
    (catch Exception e
      (log/error "Failed to open GUI:" (.getMessage e))
      (.printStackTrace e))))

;; ============================================================================
;; Registry Implementation
;; ============================================================================

(defmethod gui-registry/register-gui-handler :forge-1.20.1 [_]
  (log/info "Registering GUI handler for Forge 1.20.1")
  (register-menu-types!)
  (log/info "Forge 1.20.1 GUI handler registered"))

;; ============================================================================
;; Initialization
;; ============================================================================

(defn init!
  "Initialize Forge 1.20.1 GUI system"
  []
  (log/info "Initializing Forge 1.20.1 GUI system")
  (gui-registry/register-gui-handler :forge-1.20.1)
  (log/info "Forge 1.20.1 GUI system initialized"))

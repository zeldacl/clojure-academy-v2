(ns my-mod.forge1201.gui.registry-impl
  "Forge 1.20.1 GUI Registration Implementation

  Platform-agnostic design: Uses metadata-driven approach.

  IMPORTANT: Only imports from my-mod.gui.platform-adapter, not directly
  from my-mod.wireless.gui.* modules (per platform adapter contract)."
  (:require [my-mod.gui.platform-adapter :as gui]
            [my-mod.forge1201.gui.bridge :as bridge]
            [my-mod.config.modid :as modid]
            [my-mod.util.log :as log])
  (:import [net.minecraftforge.network NetworkHooks IContainerFactory]
           [net.minecraftforge.common.extensions IForgeMenuType]
           [net.minecraftforge.registries DeferredRegister]
           [net.minecraft.network FriendlyByteBuf]
           [net.minecraft.world.inventory MenuType]
           [net.minecraft.core.registries Registries]
           [net.minecraft.resources ResourceLocation]))

;; ============================================================================
;; MenuType Registry
;; ============================================================================

;; Registries are locked before FMLCommonSetupEvent; MenuType must be registered
;; via DeferredRegister during RegisterEvent, just like blocks and items.
(defonce menu-register
  (DeferredRegister/create Registries/MENU modid/MOD-ID))

(defonce gui-menu-types
  ^{:doc "Map from GUI ID to RegistryObject<MenuType>.
  Call (.get ro) to get the actual MenuType after registration fires.
  Structure: {gui-id RegistryObject, ...}"}
  (atom {}))

(defn get-menu-type
  "Get registered MenuType for a GUI ID.
  Resolves the RegistryObject; safe to call after RegisterEvent fires.
  
  Args:
  - gui-id: int
  
  Returns: MenuType or nil"
  [gui-id]
  (when-let [ro (get @gui-menu-types gui-id)]
    (.get ro)))

(defn create-menu-type
  "Create a MenuType for a GUI
  
  Args:
  - gui-id: int
  
  Returns: MenuType instance"
  [gui-id]
  (IForgeMenuType/create
    (reify IContainerFactory
      (create [_ window-id player-inventory buf]
        ;; This factory is invoked on the CLIENT when Forge recreates the menu
        ;; after receiving the open-screen packet.
        (let [handler (gui/get-gui-handler)
              player (.player player-inventory)
              world (.level player)
              pos (.readBlockPos buf)
              clj-container (.get-server-container handler gui-id player world pos)]
          (if clj-container
            (do
              ;; Store for screen_factory.clj which needs to read it back.
              ;; (The proxy no longer has a getClojureContainer() method since
              ;; we replaced gen-class with proxy.)
              (gui/set-client-container! clj-container)
              (bridge/wrap-clojure-container window-id (get-menu-type gui-id) clj-container))
            (do (log/error "Failed to create container for GUI" gui-id) nil)))))))

(defn register-menu-types!
  "Populate menu-register DeferredRegister with all GUI menu types.
  Must be called before menu-register is registered with the mod event bus
  (i.e. during mod-init, not during FMLCommonSetupEvent)."
  []
  (log/info "Queueing GUI menu types into DeferredRegister")
  (doseq [gui-id (gui/get-all-gui-ids)]
    (let [registry-name (gui/get-registry-name gui-id)
          ;; Create IForgeMenuType eagerly; the embedded IContainerFactory is a
          ;; lazy callback that resolves get-menu-type at GUI-open time (runtime).
          menu-type (create-menu-type gui-id)
          ;; Also publish into the platform-agnostic metadata store, because
          ;; provider_bridge.clj looks up MenuType via my-mod.gui.platform-adapter.
          _ (gui/register-menu-type! :forge-1.20.1 gui-id menu-type)
          ro (.register menu-register registry-name
               (reify java.util.function.Supplier
                 (get [_]
                   ;; Called by Forge during RegisterEvent — registries are open here.
                   ;; Store menu-type in platform adapter's metadata system
                   menu-type)))]
      (swap! gui-menu-types assoc gui-id ro)
      (log/info "Queued menu type:" registry-name "for GUI ID" gui-id)))
  (log/info "Queued" (count @gui-menu-types) "menu types"))

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

(defmethod gui/register-gui-handler :forge-1.20.1 [_]
  ;; MenuType registration is handled via DeferredRegister in mod-init.
  ;; This hook is kept for interface compliance only.
  (log/info "Forge 1.20.1 GUI handler ready (menu types registered via DeferredRegister)"))

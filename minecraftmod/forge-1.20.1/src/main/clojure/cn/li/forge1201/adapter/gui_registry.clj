(ns cn.li.forge1201.adapter.gui-registry
  "Forge 1.20.1 GUI Registration Implementation

  Platform-agnostic design: Uses metadata-driven approach.

  IMPORTANT: Only imports from `cn.li.mcmod.gui.adapter` for the unified GUI API."
  (:require [cn.li.ac.gui.platform-adapter :as gui-platform]
            [cn.li.mcmod.gui.registry-core :as gui]
            [cn.li.mcmod.gui.handler :as gui-handler]
            [cn.li.mc1201.gui.menu.bridge :as menu-core]
            [cn.li.forge1201.gui.provider-bridge :as provider-bridge]
            [cn.li.mc1201.runtime.spi.gui-registry :as registry-api]
            [cn.li.mc1201.gui.registry.common :as registry-common]
            [cn.li.mc1201.gui.registry.open :as open-core]
            [cn.li.mcmod.config :as modid]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.forge1201.shim ForgeBootstrapHelper]
           [net.minecraftforge.network NetworkHooks IContainerFactory]
           [net.minecraftforge.common.extensions IForgeMenuType]
           [net.minecraftforge.registries DeferredRegister RegistryObject]
           [net.minecraft.network FriendlyByteBuf]
           [net.minecraft.server.level ServerPlayer]
           [net.minecraft.world MenuProvider]
           [net.minecraft.world.entity.player Inventory]
           [net.minecraft.world.level Level]
           [net.minecraft.core BlockPos]))


;; ============================================================================
;; MenuType Registry
;; ============================================================================

;; Registries are locked before FMLCommonSetupEvent; MenuType must be registered
;; via DeferredRegister during RegisterEvent, just like blocks and items.
(defonce menu-register
  ;; AOT/checkClojure 阶段 Minecraft registries 尚未 bootstrapped。
  ;; 延迟创建，避免编译期触发 Bootstrap。
  (delay (ForgeBootstrapHelper/createMenusRegister modid/*mod-id*)))

(defonce gui-menu-types
  ^{:doc "Map from GUI ID to RegistryObject<MenuType>.
  Call (.get ro) to get the actual MenuType after registration fires.
  Structure: {gui-id RegistryObject, ...}"}
  (atom {}))

(declare install-registry-contract!)

(defn get-menu-type
  "Get registered MenuType for a GUI ID.
  Resolves the RegistryObject; safe to call after RegisterEvent fires.
  
  Args:
  - gui-id: int
  
  Returns: MenuType or nil"
  [gui-id]
  (when-let [^RegistryObject ro (get @gui-menu-types gui-id)]
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
              ^ServerPlayer player (.player ^Inventory player-inventory)
              ^Level world (.level player)
              ^BlockPos pos (.readBlockPos ^FriendlyByteBuf buf)]
          (registry-common/create-wrapped-container
            (fn []
              (gui-handler/get-server-container handler gui-id player world pos))
            (fn [wid menu-type clj-container]
              ;; Store for screen_factory.clj which needs to read it back.
              ;; (The proxy no longer has a getClojureContainer() method since
              ;; we replaced gen-class with proxy.)
              (gui/set-client-container! clj-container)
              (menu-core/create-menu-bridge
               wid
               menu-type
               clj-container
               {:get-slot-layout gui/get-slot-layout
                :default-player-inventory-mode :full
                :call-super-removed? false
                :remove-log-message "Menu closed for player"
                :quick-move-error-prefix "Error in quickMoveStack:"}))
            get-menu-type
            gui-id
            window-id
            "Failed to create container for GUI"))))))

(defn register-menu-types!
  "Populate menu-register DeferredRegister with all GUI menu types.
  Must be called before menu-register is registered with the mod event bus
  (i.e. during Forge bootstrap, not during FMLCommonSetupEvent)."
  []
  (log/info "Queueing GUI menu types into DeferredRegister")
  (install-registry-contract!)
  (doseq [gui-id (gui/get-all-gui-ids)]
    (let [registry-name (gui/get-registry-name gui-id)
          ;; Create IForgeMenuType eagerly; the embedded IContainerFactory is a
          ;; lazy callback that resolves get-menu-type at GUI-open time (runtime).
          menu-type (create-menu-type gui-id)
          ^DeferredRegister deferred-register (force menu-register)
          ro (.register deferred-register registry-name
               (reify java.util.function.Supplier
                 (get [_]
                   ;; Called by Forge during RegisterEvent — registries are open here.
                   ;; Store menu-type in platform adapter's metadata system
                   menu-type)))]
      (swap! gui-menu-types assoc gui-id ro)
      ;; Sync into unified business-layer metadata via mcmod (no direct `ac` dependency).
      (gui/register-menu-type! :forge-1.20.1 gui-id menu-type)
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
  [^ServerPlayer player gui-id tile-entity]
  (open-core/log-open-start! "[OPEN-GUI-FOR-PLAYER]" player gui-id tile-entity)
  (try
    (log/info "[OPEN-GUI-FOR-PLAYER] Creating MenuProvider...")
    (let [^MenuProvider provider (provider-bridge/create-menu-provider gui-id tile-entity)
          ^BlockPos pos (open-core/resolve-optional-block-pos tile-entity)]
      (log/info "[OPEN-GUI-FOR-PLAYER] MenuProvider created, pos=" pos "calling NetworkHooks...")
      (if pos
        (do
          (log/info "[OPEN-GUI-FOR-PLAYER] Opening screen with position data to client...")
          (NetworkHooks/openScreen
            player
            provider
            (reify java.util.function.Consumer
              (accept [_ buf]
                (.writeBlockPos ^FriendlyByteBuf buf pos)))))
        (do
          (log/info "[OPEN-GUI-FOR-PLAYER] Opening screen without position data...")
          (NetworkHooks/openScreen player provider)))
      (log/info "[OPEN-GUI-FOR-PLAYER] NetworkHooks called, GUI open request queued"))
    (catch Exception e
      (open-core/log-open-error! "[OPEN-GUI-FOR-PLAYER]" e))))

;; ============================================================================
;; Registry Implementation
;; ============================================================================

(defn- install-registry-contract!
  []
  (gui/register-gui-platform-impl!
    {:register-menu-type! gui-platform/register-menu-type!
     :get-menu-type gui-platform/get-menu-type
     :set-client-container! gui-platform/set-client-container!
     :clear-client-container! gui-platform/clear-client-container!
     :get-client-container gui-platform/get-client-container
     :register-active-container! gui-platform/register-active-container!
     :unregister-active-container! gui-platform/unregister-active-container!
     :register-player-container! gui-platform/register-player-container!
     :unregister-player-container! gui-platform/unregister-player-container!
     :get-player-container gui-platform/get-player-container
     :get-player-container-from-active gui-platform/get-player-container-from-active
     :get-container-for-menu gui-platform/get-container-for-menu
     :get-container-by-id gui-platform/get-container-by-id
     :get-menu-container-id gui-platform/get-menu-container-id
     :register-menu-container! gui-platform/register-menu-container!
     :unregister-menu-container! gui-platform/unregister-menu-container!
     :register-container-by-id! gui-platform/register-container-by-id!
     :unregister-container-by-id! gui-platform/unregister-container-by-id!
     :safe-tick! gui-platform/safe-tick!
     :safe-validate gui-platform/safe-validate
     :safe-sync! gui-platform/safe-sync!
     :safe-close! gui-platform/safe-close!
     :slot-count gui-platform/slot-count
     :slot-get-item gui-platform/slot-get-item
     :slot-set-item! gui-platform/slot-set-item!
     :slot-changed! gui-platform/slot-changed!
     :slot-can-place? gui-platform/slot-can-place?
     :get-container-type gui-platform/get-container-type
     :get-gui-id-for-container gui-platform/get-gui-id-for-container})
  (registry-api/register-registry-impl!
    :forge-1.20.1
    {:register-menu-type! (fn [gui-id menu-type]
                            (gui-platform/register-menu-type! :forge-1.20.1 gui-id menu-type))
     :get-menu-type get-menu-type
     :list-menu-types (fn [] @gui-menu-types)
     :invalidate-menu-registry! (fn [] (reset! gui-menu-types {}))}))

(defmethod gui/register-gui-handler :forge-1.20.1 [_]
  ;; MenuType registration is handled via DeferredRegister during Forge bootstrap.
  ;; This hook is kept for interface compliance only.
  (install-registry-contract!)
  (log/info "Forge 1.20.1 GUI handler ready (menu types registered via DeferredRegister)"))

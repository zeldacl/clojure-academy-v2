(ns cn.li.forge1201.adapter.gui-registry
  "Forge 1.20.1 GUI Registration Implementation

  Platform-agnostic design: Uses metadata-driven approach."
  (:require [cn.li.mcmod.gui.registry-core :as gui]
            [cn.li.mcmod.gui.handler :as gui-handler]
            [cn.li.mc1201.gui.menu.proxy :as menu-proxy]
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
           [net.minecraft.server.level ServerPlayer]
           [net.minecraft.world MenuProvider]
           [net.minecraft.core BlockPos]))


;; ============================================================================
;; MenuType Registry
;; ============================================================================

;; Registries are locked before FMLCommonSetupEvent; MenuType must be registered
;; via DeferredRegister during RegisterEvent, just like blocks and items.
(def ^:private gui-registry-lock
  (Object.))

(def ^:private ^:dynamic *menu-register* nil)

(def ^:private ^:dynamic *gui-menu-types*
  ^{:doc "Map from GUI ID to RegistryObject<MenuType>.
  Call (.get ro) to get the actual MenuType after registration fires.
  Structure: {gui-id RegistryObject, ...}"}
  {})

(defn menu-register
  []
  (or (var-get #'*menu-register*)
      (locking gui-registry-lock
        (or (var-get #'*menu-register*)
            (let [created (ForgeBootstrapHelper/createMenusRegister modid/*mod-id*)]
              (alter-var-root #'*menu-register* (constantly created))
              created)))))

(defn- gui-menu-types-snapshot
  []
  (var-get #'*gui-menu-types*))

(defn- assoc-gui-menu-type!
  [gui-id menu-type]
  (locking gui-registry-lock
    (alter-var-root #'*gui-menu-types* assoc gui-id menu-type)
    nil))

(defn- clear-gui-menu-types!
  []
  (locking gui-registry-lock
    (alter-var-root #'*gui-menu-types* (constantly {}))
    nil))

(declare install-registry-contract!)

(defn get-menu-type
  "Get registered MenuType for a GUI ID.
  Resolves the RegistryObject; safe to call after RegisterEvent fires.
  
  Args:
  - gui-id: int
  
  Returns: MenuType or nil"
  [gui-id]
  (when-let [registered (get (gui-menu-types-snapshot) gui-id)]
    (if (instance? RegistryObject registered)
      (.get ^RegistryObject registered)
      registered)))

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
              pos (registry-common/read-block-pos buf)]
          (registry-common/create-client-menu!
            {:gui-id gui-id
             :window-id window-id
             :player-inventory player-inventory
             :pos pos
             :handler handler
             :create-container-fn (fn [h gid p world block-pos]
                                    (gui-handler/get-server-container h gid p world block-pos))
             :create-menu-proxy-fn (fn [wid menu-type clj-container opts]
                                     (menu-proxy/create-menu-proxy wid menu-type clj-container opts))
             :resolve-menu-type-fn get-menu-type
             :bridge-opts (menu-proxy/platform-menu-proxy-opts :forge-1.20.1)
             :error-prefix "Failed to create container for GUI"}))))))

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
          ^DeferredRegister deferred-register (menu-register)
          ro (.register deferred-register registry-name
               (reify java.util.function.Supplier
                 (get [_]
                   ;; Called by Forge during RegisterEvent — registries are open here.
                   ;; Store menu-type in platform adapter's metadata system
                   menu-type)))]
      (assoc-gui-menu-type! gui-id ro)
      (log/info "Queued menu type:" registry-name "for GUI ID" gui-id)))
      (log/info "Queued" (count (gui-menu-types-snapshot)) "menu types"))

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
                (registry-common/write-block-pos! buf pos)))))
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
  (registry-api/register-registry-impl!
    :forge-1.20.1
    {:register-menu-type! (fn [gui-id menu-type]
                            (assoc-gui-menu-type! gui-id menu-type)
                            nil)
     :get-menu-type get-menu-type
     :list-menu-types (fn [] (gui-menu-types-snapshot))
     :invalidate-menu-registry! clear-gui-menu-types!}))

(defmethod gui/register-gui-handler :forge-1.20.1 [_]
  ;; MenuType registration is handled via DeferredRegister during Forge bootstrap.
  ;; This hook is kept for interface compliance only.
  (install-registry-contract!)
  (log/info "Forge 1.20.1 GUI handler ready (menu types registered via DeferredRegister)"))

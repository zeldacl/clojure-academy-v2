(ns cn.li.fabric1201.adapter.gui-registry
  "Fabric 1.20.1 GUI Registration Implementation"
  (:require [cn.li.mcmod.gui.registry-core :as gui]
            [cn.li.mc1201.gui.menu.bridge :as menu-core]
            [cn.li.fabric1201.gui.provider-bridge :as provider-bridge]
            [cn.li.mc1201.runtime.spi.gui-registry :as registry-api]
            [cn.li.mc1201.gui.registry.common :as registry-common]
            [cn.li.mc1201.gui.registry.open :as open-core]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.resources ResourceLocation]
           [net.fabricmc.fabric.api.screenhandler.v1 ScreenHandlerRegistry]))

(defonce gui-handler-types
  ^{:doc "Map from GUI ID to registered MenuType instances"}
  (atom {}))

(defn get-handler-type [gui-id]
  (get @gui-handler-types gui-id))

(defn create-extended-screen-handler-type [gui-id]
  (let [registry-name (gui/get-registry-name gui-id)]
    (ScreenHandlerRegistry/registerExtended
      (ResourceLocation. modid/MOD-ID registry-name)
      (reify net.fabricmc.fabric.api.screenhandler.v1.ScreenHandlerRegistry$ExtendedClientHandlerFactory
        (create [_ sync-id player-inventory buf]
          (let [gui-id-from-buf (.readInt buf)
                has-tile (.readBoolean buf)
                pos (when has-tile (.readBlockPos buf))
                handler (gui/get-gui-handler)
                player (.player player-inventory)
                world (.level player)]
            (registry-common/create-wrapped-container
              (fn []
                (.get-server-container handler gui-id-from-buf player world pos))
              (fn [window-id menu-type clj-container]
                (menu-core/create-menu-bridge
                 window-id
                 menu-type
                 clj-container
                 {:get-slot-layout gui/get-slot-layout
                  :default-player-inventory-mode :full
                  :call-super-removed? true
                  :remove-log-message "Fabric menu closed for player"
                  :quick-move-error-prefix "Error in Fabric quickMoveStack:"}))
              get-handler-type
              gui-id-from-buf
              sync-id
              "Failed to create container for GUI")))))))

(defn register-screen-handler-types! []
  (log/info "Registering GUI screen handler types for Fabric 1.20.1")
  (doseq [gui-id (gui/get-all-gui-ids)]
    (let [handler-type (create-extended-screen-handler-type gui-id)
          registry-name (gui/get-registry-name gui-id)]
      (swap! gui-handler-types assoc gui-id handler-type)
      (gui/register-menu-type! :fabric-1.20.1 gui-id handler-type)
      (log/info "Registered screen handler type:" registry-name "for GUI ID" gui-id)))
  (log/info "Registered" (count @gui-handler-types) "screen handler types"))

(defn open-gui-for-player [player gui-id tile-entity]
  (open-core/log-open-start! "[FABRIC-OPEN-GUI]" player gui-id tile-entity)
  (try
    (let [factory (provider-bridge/create-extended-menu-provider gui-id tile-entity)]
      (open-core/open-player-menu-with-fallback! player factory)
      (open-core/log-open-success! "[FABRIC-OPEN-GUI]"))
    (catch Exception e
      (open-core/log-open-error! "[FABRIC-OPEN-GUI]" e))))

(defn- install-registry-contract!
  []
  (registry-api/register-registry-impl!
    :fabric-1.20.1
    {:register-menu-type! (fn [gui-id menu-type]
                            (gui/register-menu-type! :fabric-1.20.1 gui-id menu-type))
     :get-menu-type get-handler-type
     :list-menu-types (fn [] @gui-handler-types)
     :invalidate-menu-registry! (fn [] (reset! gui-handler-types {}))}))

(defmethod gui/register-gui-handler :fabric-1.20.1 [_]
  (log/info "Registering GUI handler for Fabric 1.20.1")
  (install-registry-contract!)
  (register-screen-handler-types!)
  (log/info "Fabric 1.20.1 GUI handler registered"))

(defn init! []
  (log/info "Initializing Fabric 1.20.1 GUI system")
  (gui/register-gui-handler :fabric-1.20.1)
  (log/info "Fabric 1.20.1 GUI system initialized"))

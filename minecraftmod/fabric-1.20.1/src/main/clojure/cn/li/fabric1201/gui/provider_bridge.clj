(ns cn.li.fabric1201.gui.provider-bridge
  "Fabric 1.20.1 provider bridge.

  Uses reify factories and delegates menu construction to shared provider dispatcher."
  (:require [cn.li.mc1201.gui.provider.dispatcher :as provider-dispatcher]
            [cn.li.mc1201.gui.menu.proxy :as menu-proxy]
            [cn.li.mc1201.gui.provider.common :as provider-common]
            [cn.li.mc1201.gui.registry.common :as registry-common]
            [cn.li.mcmod.gui.registry-core :as gui])
  (:import [net.minecraft.world MenuProvider]
           [net.fabricmc.fabric.api.screenhandler.v1 ExtendedScreenHandlerFactory]
           [net.minecraft.network.chat Component]))

(defn- create-menu-proxy
  ([window-id menu-type clj-container]
  (create-menu-proxy window-id menu-type clj-container nil))
  ([window-id menu-type clj-container opts]
  (menu-proxy/create-menu-proxy
    window-id
    menu-type
    clj-container
    (merge {:get-slot-layout gui/get-slot-layout
            :default-player-inventory-mode :full
            :call-super-removed? true
            :remove-log-message "Fabric menu closed for player"
            :quick-move-error-prefix "Error in Fabric quickMoveStack:"}
           opts))))

(defn create-menu-provider [gui-id tile-entity]
  (reify MenuProvider
    (getDisplayName [_]
      (Component/literal (gui/get-display-name gui-id)))
    (createMenu [_ sync-id _player-inventory player]
      (provider-dispatcher/create-menu-from-provider!
       {:gui-id gui-id
        :tile-entity tile-entity
        :window-id sync-id
        :player player
        :platform-key :fabric-1.20.1
        :create-container-fn (fn [handler gid p world pos]
                               (.get-server-container handler gid p world pos))
        :create-menu-proxy-fn create-menu-proxy
        :log-prefix "[FABRIC-MENU-PROVIDER]"}))))

(defn create-extended-menu-provider [gui-id tile-entity]
  (reify ExtendedScreenHandlerFactory
    (getDisplayName [_]
      (Component/literal (gui/get-display-name gui-id)))
    (createMenu [_ sync-id _player-inventory player]
      (provider-dispatcher/create-menu-from-provider!
       {:gui-id gui-id
        :tile-entity tile-entity
        :window-id sync-id
        :player player
        :platform-key :fabric-1.20.1
        :create-container-fn (fn [handler gid p world pos]
                               (.get-server-container handler gid p world pos))
        :create-menu-proxy-fn create-menu-proxy
        :log-prefix "[FABRIC-MENU-PROVIDER]"}))
    (writeScreenOpeningData [_ player buf]
      (registry-common/write-extended-open-payload!
        buf
        gui-id
        (when tile-entity
          (provider-common/tile->pos tile-entity player))))))

(defn create-extended-screen-handler-factory [gui-id tile-entity]
  (create-extended-menu-provider gui-id tile-entity))

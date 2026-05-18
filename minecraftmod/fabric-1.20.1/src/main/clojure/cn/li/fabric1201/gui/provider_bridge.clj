(ns cn.li.fabric1201.gui.provider-bridge
  "Fabric 1.20.1 provider bridge.

  Uses reify factories and delegates menu construction to shared menu-bridge core."
  (:require [cn.li.mc1201.gui.provider.bridge :as provider-core]
            [cn.li.mc1201.gui.menu.bridge :as menu-core]
            [cn.li.mc1201.gui.provider.common :as provider-common]
            [cn.li.mcmod.gui.registry-core :as gui])
  (:import [net.minecraft.world MenuProvider]
           [net.fabricmc.fabric.api.screenhandler.v1 ExtendedScreenHandlerFactory]
           [net.minecraft.network.chat Component]))

(defn- create-menu-bridge
  [window-id menu-type clj-container]
  (menu-core/create-menu-bridge
   window-id
   menu-type
   clj-container
   {:get-slot-layout gui/get-slot-layout
    :default-player-inventory-mode :full
    :call-super-removed? true
    :remove-log-message "Fabric menu closed for player"
    :quick-move-error-prefix "Error in Fabric quickMoveStack:"}))

(defn create-menu-provider [gui-id tile-entity]
  (reify MenuProvider
    (getDisplayName [_]
      (Component/literal (gui/get-display-name gui-id)))
    (createMenu [_ sync-id _player-inventory player]
      (provider-core/create-menu-from-provider!
       {:gui-id gui-id
        :tile-entity tile-entity
        :window-id sync-id
        :player player
        :platform-key :fabric-1.20.1
        :create-container-fn (fn [handler gid p world pos]
                               (.get-server-container handler gid p world pos))
        :create-menu-bridge-fn create-menu-bridge
        :log-prefix "[FABRIC-MENU-PROVIDER]"}))))

(defn create-extended-menu-provider [gui-id tile-entity]
  (reify ExtendedScreenHandlerFactory
    (getDisplayName [_]
      (Component/literal (gui/get-display-name gui-id)))
    (createMenu [_ sync-id _player-inventory player]
      (provider-core/create-menu-from-provider!
       {:gui-id gui-id
        :tile-entity tile-entity
        :window-id sync-id
        :player player
        :platform-key :fabric-1.20.1
        :create-container-fn (fn [handler gid p world pos]
                               (.get-server-container handler gid p world pos))
        :create-menu-bridge-fn create-menu-bridge
        :log-prefix "[FABRIC-MENU-PROVIDER]"}))
    (writeScreenOpeningData [_ player buf]
      (.writeInt buf gui-id)
      (if tile-entity
        (let [pos (provider-common/tile->pos tile-entity player)]
          (.writeBoolean buf true)
          (.writeBlockPos buf pos))
        (.writeBoolean buf false)))))

(defn create-extended-screen-handler-factory [gui-id tile-entity]
  (create-extended-menu-provider gui-id tile-entity))

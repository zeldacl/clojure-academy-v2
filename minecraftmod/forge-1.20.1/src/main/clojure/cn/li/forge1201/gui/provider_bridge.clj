(ns cn.li.forge1201.gui.provider-bridge
  "Forge 1.20.1 provider bridge.

  Uses reify MenuProvider and delegates menu creation to shared menu-bridge core."
  (:require [cn.li.mc1201.gui.provider.bridge :as provider-core]
            [cn.li.mc1201.gui.menu.bridge :as menu-core]
            [cn.li.mcmod.gui.registry-core :as gui]
            [cn.li.mcmod.gui.handler :as gui-handler]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.world MenuProvider]
           [net.minecraft.network.chat Component]))

(defn- create-menu-bridge
  [window-id menu-type clj-container]
  (menu-core/create-menu-bridge
   window-id
   menu-type
   clj-container
   {:get-slot-layout gui/get-slot-layout
    :default-player-inventory-mode :full
    :call-super-removed? false
    :remove-log-message "Menu closed for player"
    :quick-move-error-prefix "Error in quickMoveStack:"}))

(defn create-menu-provider
  "Create a MenuProvider for opening GUI.

  Args:
  - gui-id: int (GUI identifier)
  - tile-entity: TileEntity instance

  Returns: MenuProvider instance"
  [gui-id tile-entity]
  (reify MenuProvider
    (getDisplayName [_]
      ;(Component/literal (gui/get-display-name gui-id))
      (Component/empty)
      )

    (createMenu [_ window-id _player-inventory player]
      (try
        (provider-core/create-menu-from-provider!
         {:gui-id gui-id
          :tile-entity tile-entity
          :window-id window-id
          :player player
          :platform-key :forge-1.20.1
          :create-container-fn (fn [handler gid p world pos]
                                 (gui-handler/get-server-container handler gid p world pos))
          :create-menu-bridge-fn create-menu-bridge
          :log-prefix "[MENU-PROVIDER]"})
        (catch Exception e
          (log/error "[MENU-PROVIDER] Error creating menu:" (.getMessage e))
          (log/error "[MENU-PROVIDER] Stack trace:" e)
          (throw e))))))

(defn create-extended-menu-provider
  "Forge has no separate extended provider type; keep a unified API."
  [gui-id tile-entity]
  (create-menu-provider gui-id tile-entity))
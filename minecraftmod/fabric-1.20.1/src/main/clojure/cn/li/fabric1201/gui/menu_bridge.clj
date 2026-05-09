(ns cn.li.fabric1201.gui.menu-bridge
  "Fabric 1.20.1 AbstractContainerMenu wrapper.

  Uses runtime `proxy` + a tiny Java bridge class (CMenuBridge) so protected
  AbstractContainerMenu APIs can be invoked from Clojure safely."
  (:require [cn.li.mcmod.gui.adapter :as gui]
            [cn.li.fabric1201.gui.slots :as slots]
            [cn.li.mc1201.gui.container-adapter :as ca]
            [cn.li.mc1201.gui.menu-bridge-common :as menu-common])
  (:import [cn.li.mc1201.gui CMenuBridge]
           [net.minecraft.server.level ServerPlayer]))

(defn- setup-menu-slots!
  [^CMenuBridge menu clj-container]
  (let [gui-id (gui/get-gui-id-for-container clj-container)
        ^ServerPlayer player (:player clj-container)
        player-inventory (when player (.getInventory player))
        tile-inventory (ca/create-tile-inventory-adapter clj-container)]
    (when (and gui-id player-inventory)
      (slots/add-gui-slots menu tile-inventory gui-id 0 0 nil)
      (slots/add-player-inventory-slots menu player-inventory 6 105 nil))))

(defn create-menu-bridge
  [window-id menu-type clj-container]
  (let [menu (proxy [CMenuBridge] [menu-type (int window-id)]
               (stillValid [player]
                 (boolean (gui/safe-validate clj-container player)))

               (removed [player]
                 (menu-common/remove-menu!
                   this
                   clj-container
                   player
                   {:call-super-removed? true
                    :log-message "Fabric menu closed for player"}))

               (broadcastChanges []
                 (menu-common/broadcast-and-sync! this clj-container nil))

               (clicked [slot-index button click-type player]
                 (let [^CMenuBridge s this]
                   (.callSuperClicked s slot-index button click-type player)))

               (quickMoveStack [player slot-index]
                 (menu-common/quick-move-stack this clj-container slot-index "Error in Fabric quickMoveStack:")))]
    (setup-menu-slots! menu clj-container)
    (menu-common/finalize-menu-registration! menu window-id clj-container)))

(defn create-screen-handler-bridge [window-id menu-type clj-container]
  (create-menu-bridge window-id menu-type clj-container))

(ns cn.li.fabric1201.gui.menu-bridge
  "Fabric 1.20.1 AbstractContainerMenu wrapper.

  Uses runtime `proxy` + a tiny Java bridge class (CMenuBridge) so protected
  AbstractContainerMenu APIs can be invoked from Clojure safely."
  (:require [cn.li.mcmod.gui.adapter :as gui]
            [cn.li.fabric1201.gui.slots :as slots]
            [cn.li.mc1201.gui.container-adapter :as ca]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.fabric1201.gui CMenuBridge]
           [net.minecraft.server.level ServerPlayer]
           [net.minecraft.world.inventory AbstractContainerMenu Slot]
           [net.minecraft.world.item ItemStack]
           [net.minecraft.world Container]))

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
                 (let [cid (gui/get-menu-container-id this)]
                   (when cid
                     (gui/unregister-container-by-id! cid)))
                 (gui/unregister-menu-container! this)
                 (gui/safe-close! clj-container)
                 (gui/unregister-active-container! clj-container)
                 (gui/unregister-player-container! player)
                 (let [^CMenuBridge s this]
                   (.callSuperRemoved s player))
                 (log/info "Fabric menu closed for player" (str player)))

               (broadcastChanges []
                 (let [^CMenuBridge s this]
                   (.callSuperBroadcastChanges s))
                 (gui/safe-sync! clj-container))

               (clicked [slot-index button click-type player]
                 (let [^CMenuBridge s this]
                   (.callSuperClicked s slot-index button click-type player)))

               (quickMoveStack [player slot-index]
                 (try
                   (let [^Slot slot (.getSlot ^AbstractContainerMenu this slot-index)]
                     (if (and slot (.hasItem slot))
                       (let [^ItemStack stack (.getItem slot)]
                         (gui/execute-quick-move-forge this clj-container slot-index slot stack))
                       ItemStack/EMPTY))
                   (catch Exception e
                     (log/error "Error in Fabric quickMoveStack:" (.getMessage e))
                     ItemStack/EMPTY))))]
    (setup-menu-slots! menu clj-container)
    (gui/register-menu-container! menu clj-container)
    (gui/register-container-by-id! window-id clj-container)
    menu))

(defn create-screen-handler-bridge [window-id menu-type clj-container]
  (create-menu-bridge window-id menu-type clj-container))

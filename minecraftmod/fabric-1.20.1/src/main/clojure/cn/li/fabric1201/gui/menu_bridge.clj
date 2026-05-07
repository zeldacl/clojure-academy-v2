(ns cn.li.fabric1201.gui.menu-bridge
  "Fabric 1.20.1 AbstractContainerMenu wrapper.

  Uses runtime `proxy` + a tiny Java bridge class (CMenuBridge) so protected
  AbstractContainerMenu APIs can be invoked from Clojure safely."
  (:require [cn.li.mcmod.gui.adapter :as gui]
            [cn.li.fabric1201.gui.slots :as slots]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.fabric1201.gui CMenuBridge]
           [net.minecraft.server.level ServerPlayer]
           [net.minecraft.world.inventory AbstractContainerMenu Slot]
           [net.minecraft.world.item ItemStack]
           [net.minecraft.world Container]))

(defn- create-tile-inventory-adapter
  [clj-container]
  (reify Container
    (getContainerSize [_]
      (int (or (gui/slot-count clj-container) 0)))

    (isEmpty [_]
      (let [n (int (or (gui/slot-count clj-container) 0))]
        (not-any? (fn [idx]
                    (let [stack (gui/slot-get-item clj-container idx)]
                      (and stack (not (.isEmpty ^ItemStack stack)))))
                  (range n))))

    (getItem [_ slot]
      (or (gui/slot-get-item clj-container (int slot))
          ItemStack/EMPTY))

    (removeItem [_ slot amount]
      (let [slot (int slot)
            amount (int amount)
            current (or (gui/slot-get-item clj-container slot)
                        ItemStack/EMPTY)]
        (if (or (nil? current) (.isEmpty ^ItemStack current) (<= amount 0))
          ItemStack/EMPTY
          (let [taken (.split ^ItemStack current amount)]
            (if (.isEmpty ^ItemStack current)
              (gui/slot-set-item! clj-container slot nil)
              (gui/slot-set-item! clj-container slot current))
            (gui/slot-changed! clj-container slot)
            taken))))

    (removeItemNoUpdate [_ slot]
      (let [slot (int slot)
            current (or (gui/slot-get-item clj-container slot)
                        ItemStack/EMPTY)]
        (gui/slot-set-item! clj-container slot nil)
        (gui/slot-changed! clj-container slot)
        current))

    (setItem [_ slot stack]
      (gui/slot-set-item! clj-container (int slot) stack)
      (gui/slot-changed! clj-container (int slot)))

    (setChanged [_]
      (let [n (int (or (gui/slot-count clj-container) 0))]
        (doseq [idx (range n)]
          (gui/slot-changed! clj-container idx))))

    (stillValid [_ player]
      (boolean (gui/safe-validate clj-container player)))

    (canPlaceItem [_ slot stack]
      (boolean (gui/slot-can-place? clj-container (int slot) stack)))

    (clearContent [_]
      (let [n (int (or (gui/slot-count clj-container) 0))]
        (doseq [idx (range n)]
          (gui/slot-set-item! clj-container idx nil))
        (doseq [idx (range n)]
          (gui/slot-changed! clj-container idx))))))

(defn- setup-menu-slots!
  [^CMenuBridge menu clj-container]
  (let [gui-id (gui/get-gui-id-for-container clj-container)
        ^ServerPlayer player (:player clj-container)
        player-inventory (when player (.getInventory player))
        tile-inventory (create-tile-inventory-adapter clj-container)]
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

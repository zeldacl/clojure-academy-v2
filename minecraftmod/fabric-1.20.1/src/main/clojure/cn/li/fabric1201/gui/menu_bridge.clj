(ns cn.li.fabric1201.gui.menu-bridge
  "Fabric 1.20.1 AbstractContainerMenu wrapper.

  Uses runtime `proxy` + a tiny Java bridge class (CMenuBridge) so protected
  AbstractContainerMenu APIs can be invoked from Clojure safely."
  (:require [cn.li.mcmod.gui.adapter :as gui]
            [cn.li.mcmod.gui.tabbed-gui :as tabbed]
            [cn.li.fabric1201.gui.slots :as slots]
            [cn.li.mc1201.gui.container-adapter :as ca]
            [cn.li.mc1201.gui.menu-bridge-common :as menu-common])
  (:import [cn.li.mc1201.gui CMenuBridge]
           [net.minecraft.server.level ServerPlayer]
           [net.minecraft.world.inventory DataSlot]
           [net.minecraft.world.item ItemStack]))

(defn- create-tab-data-slot
  "Create a standalone DataSlot and sync from container's :tab-index."
  [clj-container]
  (doto (DataSlot/standalone)
    (.set (int @(:tab-index clj-container)))))

(defn- sync-tab-slot-from-container!
  "Update tab DataSlot from container so broadcastChanges sends current :tab-index to client."
  [^DataSlot tab-slot clj-container]
  (when (and tab-slot clj-container (:tab-index clj-container))
    (.set tab-slot (int @(:tab-index clj-container)))))

(defn- sync-data-slots-from-container!
  "Update all business DataSlots (e.g. plate-count/core-level) from container atoms."
  [clj-container]
  (when-let [data-slots (:data-slots clj-container)]
    (doseq [[k ^DataSlot slot] data-slots]
      (when-let [atom-ref (get clj-container k)]
        (.set slot (int @atom-ref))))))

(defn- setup-menu-slots!
  [^CMenuBridge menu clj-container tab-slot]
  (let [gui-id (gui/get-gui-id-for-container clj-container)
        ^ServerPlayer player (:player clj-container)
        player-inventory (when player (.getInventory player))
        tile-inventory (ca/create-tile-inventory-adapter clj-container)
        tabbed? (tabbed/tabbed-container? clj-container)
        active?-fn (when tabbed? (fn [] (tabbed/slots-active? clj-container)))]
    (when tab-slot
      (.addDataSlotPublic menu tab-slot))
    (when-let [data-slots (:data-slots clj-container)]
      (doseq [^DataSlot data-slot (vals data-slots)]
        (.addDataSlotPublic menu data-slot)))
    (when (and gui-id player-inventory)
      (slots/add-gui-slots menu tile-inventory gui-id 0 0 (when tabbed? active?-fn))
      (slots/add-player-inventory-slots menu player-inventory 6 105 (when tabbed? active?-fn)))))

(defn create-menu-bridge
  [window-id menu-type clj-container]
  (let [tab-slot (when (tabbed/tabbed-container? clj-container)
                   (create-tab-data-slot clj-container))
        menu (proxy [CMenuBridge] [menu-type (int window-id)]
               (stillValid [player]
                 (boolean (gui/safe-validate clj-container player)))

               (removed [player]
                 (menu-common/remove-menu!
                   this
                   clj-container
                   player
                   {:on-container-id tabbed/clear-tab-index-by-container-id!
                    :call-super-removed? true
                    :log-message "Fabric menu closed for player"}))

               (broadcastChanges []
                 (menu-common/broadcast-and-sync!
                   this
                   clj-container
                   (fn []
                     (sync-tab-slot-from-container! tab-slot clj-container)
                     (sync-data-slots-from-container! clj-container))))

               (clicked [slot-index button click-type player]
                 (when (or (not (tabbed/tabbed-container? clj-container))
                           (tabbed/slots-active-for-menu? this clj-container))
                   (let [^CMenuBridge s this]
                     (.callSuperClicked s slot-index button click-type player))))

               (quickMoveStack [player slot-index]
                 (if (and (tabbed/tabbed-container? clj-container)
                          (not (tabbed/slots-active-for-menu? this clj-container)))
                   ItemStack/EMPTY
                   (menu-common/quick-move-stack this clj-container slot-index "Error in Fabric quickMoveStack:"))))]
    (setup-menu-slots! menu clj-container tab-slot)
    (menu-common/finalize-menu-registration! menu window-id clj-container)))

(defn create-screen-handler-bridge [window-id menu-type clj-container]
  (create-menu-bridge window-id menu-type clj-container))

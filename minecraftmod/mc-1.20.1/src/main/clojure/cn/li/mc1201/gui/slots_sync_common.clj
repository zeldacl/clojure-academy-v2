(ns cn.li.mc1201.gui.slots-sync-common
  "Shared slot/data-slot setup logic for menu bridges."
  (:require [cn.li.mcmod.gui.registry-core :as gui]
            [cn.li.mcmod.gui.tabbed-gui :as tabbed]
            [cn.li.mc1201.gui.container-adapter :as ca]
            [cn.li.mc1201.gui.slots-common :as slots-common])
  (:import [cn.li.mc1201.gui CMenuBridge]
           [net.minecraft.server.level ServerPlayer]
           [net.minecraft.world.inventory DataSlot]))

(defn sync-data-slots-from-container!
  [clj-container]
  (when-let [data-slots (:data-slots clj-container)]
    (doseq [[k ^DataSlot slot] data-slots]
      (when-let [atom-ref (get clj-container k)]
        (.set slot (int @atom-ref))))))

(defn setup-menu-slots!
  [^CMenuBridge menu clj-container tab-slot {:keys [get-slot-layout default-player-inventory-mode]
                                             :or {get-slot-layout gui/get-slot-layout
                                                  default-player-inventory-mode :full}}]
  (let [gui-id (gui/get-gui-id-for-container clj-container)
        ^ServerPlayer player (:player clj-container)
        player-inventory (when player (.getInventory player))
        slot-layout (when gui-id (get-slot-layout gui-id))
        player-inventory-mode (keyword (or (:player-inventory-mode slot-layout)
                                           default-player-inventory-mode
                                           :full))
        tile-inventory (ca/create-tile-inventory-adapter clj-container)
        tabbed? (tabbed/tabbed-container? clj-container)
        active?-fn (when tabbed? (fn [] (tabbed/slots-active? clj-container)))]
    (when tab-slot
      (.addDataSlotPublic menu tab-slot))
    (when-let [data-slots (:data-slots clj-container)]
      (doseq [^DataSlot data-slot (vals data-slots)]
        (.addDataSlotPublic menu data-slot)))
    (when (and gui-id player-inventory)
      (slots-common/add-gui-slots!
        (fn [slot] (.addSlotPublic menu slot))
        get-slot-layout
        tile-inventory
        gui-id
        0
        0
        (when tabbed? active?-fn))
      (case player-inventory-mode
        :none nil
        :hotbar-only (slots-common/add-player-hotbar-slots!
                       (fn [slot] (.addSlotPublic menu slot))
                       player-inventory
                       6
                       105
                       (when tabbed? active?-fn))
        (slots-common/add-player-inventory-slots!
          (fn [slot] (.addSlotPublic menu slot))
          player-inventory
          6
          105
          (when tabbed? active?-fn))))))

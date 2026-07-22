(ns cn.li.mc1201.gui.slots.sync
  "Shared slot/data-slot setup logic for menu bridges."
  (:require [cn.li.mcmod.gui.registry :as gui-reg]
            [cn.li.mcmod.gui.adapter.platform-registry :as platform]
            [cn.li.mcmod.gui.tabbed-gui :as tabbed]
            [cn.li.mc1201.gui.menu.container :as ca]
            [cn.li.mc1201.gui.slots.common :as slots-common]
            [cn.li.mc1201.gui.slots.data-slot :as data-slot])
  (:import [cn.li.mc1201.gui CMenuBridge]
           [net.minecraft.server.level ServerPlayer]))

(defn ensure-container-data-slots!
  "Materialize atom-backed DataSlots on container when specs are present."
  [clj-container]
  (if (and (:data-slot-field-specs clj-container)
           (empty? (:data-slots clj-container)))
    (data-slot/materialize-data-slots! clj-container (:data-slot-field-specs clj-container))
    clj-container))

(defn setup-menu-slots!
  [^CMenuBridge menu clj-container _tab-slot {:keys [get-slot-layout default-player-inventory-mode]
                                               :or {get-slot-layout gui-reg/get-slot-layout
                                                    default-player-inventory-mode :full}}]
  (let [clj-container (ensure-container-data-slots! clj-container)
        gui-id (platform/get-gui-id-for-container clj-container)
        ^ServerPlayer player (:player clj-container)
        player-inventory (when player (.getInventory player))
        slot-layout (when gui-id (get-slot-layout gui-id))
        player-inventory-mode (keyword (or (:player-inventory-mode slot-layout)
                                           default-player-inventory-mode
                                           :full))
        tile-inventory (ca/create-tile-inventory-adapter clj-container)
        tabbed? (tabbed/tabbed-container? clj-container)
        active?-fn (when tabbed? (fn [] (tabbed/slots-active? clj-container)))]
    (data-slot/register-data-slots-on-menu! menu clj-container)
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

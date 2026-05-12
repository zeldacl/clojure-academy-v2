(ns cn.li.mc1201.gui.menu-bridge-core
  "Shared menu-bridge proxy builder for platform adapters.

  Platform namespaces provide small callback/config differences (slot add callback,
  loader-specific remove semantics, player inventory mode defaults), while all menu
  proxy and slot/data-slot sync logic lives here."
  (:require [cn.li.mcmod.gui.adapter :as gui]
            [cn.li.mcmod.gui.tabbed-gui :as tabbed]
            [cn.li.mc1201.gui.container-adapter :as ca]
            [cn.li.mc1201.gui.menu-bridge-common :as menu-common]
            [cn.li.mc1201.gui.slots-common :as slots-common])
  (:import [cn.li.mc1201.gui CMenuBridge]
           [net.minecraft.server.level ServerPlayer]
           [net.minecraft.world.inventory DataSlot]
           [net.minecraft.world.item ItemStack]))

(defn- create-tab-data-slot
  [clj-container]
  (doto (DataSlot/standalone)
    (.set (int @(:tab-index clj-container)))))

(defn- sync-tab-slot-from-container!
  [^DataSlot tab-slot clj-container]
  (when (and tab-slot clj-container (:tab-index clj-container))
    (.set tab-slot (int @(:tab-index clj-container)))))

(defn- sync-data-slots-from-container!
  [clj-container]
  (when-let [data-slots (:data-slots clj-container)]
    (doseq [[k ^DataSlot slot] data-slots]
      (when-let [atom-ref (get clj-container k)]
        (.set slot (int @atom-ref))))))

(defn- setup-menu-slots!
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

(defn create-menu-bridge
  "Create a CMenuBridge proxy around a Clojure container.

  Optional opts:
  - :get-slot-layout          fn [gui-id] -> layout map (default gui/get-slot-layout)
  - :default-player-inventory-mode  :full/:hotbar-only/:none (default :full)
  - :call-super-removed?      boolean
  - :remove-log-message       string
  - :quick-move-error-prefix  string"
  [window-id menu-type clj-container {:keys [get-slot-layout
                                             default-player-inventory-mode
                                             call-super-removed?
                                             remove-log-message
                                             quick-move-error-prefix]
                                      :or {get-slot-layout gui/get-slot-layout
                                           default-player-inventory-mode :full
                                           call-super-removed? false
                                           remove-log-message "Menu closed for player"
                                           quick-move-error-prefix "Error in quickMoveStack:"}}]
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
                   :call-super-removed? call-super-removed?
                   :log-message remove-log-message}))

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
                   (menu-common/quick-move-stack this clj-container slot-index quick-move-error-prefix)))

               (canTakeItemForPickAll [_stack _slot] true)
               (canDragTo [_slot] true))]
    (setup-menu-slots! menu clj-container tab-slot {:get-slot-layout get-slot-layout
                                                     :default-player-inventory-mode default-player-inventory-mode})
    (menu-common/finalize-menu-registration! menu window-id clj-container)))
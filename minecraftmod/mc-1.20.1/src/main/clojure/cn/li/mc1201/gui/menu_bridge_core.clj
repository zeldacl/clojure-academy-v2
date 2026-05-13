(ns cn.li.mc1201.gui.menu-bridge-core
  "Shared menu-bridge proxy builder for platform adapters.

  Platform namespaces provide small callback/config differences (slot add callback,
  loader-specific remove semantics, player inventory mode defaults), while all menu
  proxy and slot/data-slot sync logic lives here."
  (:require [cn.li.mcmod.gui.registry-core :as gui]
            [cn.li.mcmod.gui.tabbed-gui :as tabbed]
            [cn.li.mc1201.gui.tabbed-slots-core :as tabbed-slots-core]
            [cn.li.mc1201.gui.slots-sync-common :as slots-sync-common]
            [cn.li.mc1201.gui.menu-bridge-common :as menu-common]
            [cn.li.mc1201.gui.slots-common :as slots-common])
  (:import [cn.li.mc1201.gui CMenuBridge]
           [net.minecraft.server.level ServerPlayer]
           [net.minecraft.world.inventory DataSlot]
           [net.minecraft.world.item ItemStack]))

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
                   (tabbed-slots-core/create-tab-data-slot clj-container))
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
                    (tabbed-slots-core/sync-tab-slot-from-container! tab-slot clj-container)
                    (slots-sync-common/sync-data-slots-from-container! clj-container))))

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
    (slots-sync-common/setup-menu-slots! menu clj-container tab-slot {:get-slot-layout get-slot-layout
                                       :default-player-inventory-mode default-player-inventory-mode})
    (menu-common/finalize-menu-registration! menu window-id clj-container)))
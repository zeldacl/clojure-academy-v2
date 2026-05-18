(ns cn.li.mc1201.gui.menu.proxy
  "Shared CMenuBridge proxy builder for platform adapters.

  Platform namespaces provide small callback/config differences (slot add callback,
  loader-specific remove semantics, player inventory mode defaults), while all menu
  proxy and slot/data-slot sync logic lives here."
  (:require [cn.li.mcmod.gui.registry-core :as gui]
            [cn.li.mcmod.gui.tabbed-gui :as tabbed]
            [cn.li.mc1201.gui.slots.tabbed :as tabbed-slots]
            [cn.li.mc1201.gui.slots.sync :as slots-sync]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.mc1201.gui CMenuBridge]
           [net.minecraft.world.inventory AbstractContainerMenu Slot]
           [net.minecraft.world.item ItemStack]))

(defn- remove-menu!
  [this clj-container player {:keys [on-container-id
                                     call-super-removed?
                                     log-message]
                              :or {call-super-removed? false
                                   log-message "Menu closed for player"}}]
  (let [cid (gui/get-menu-container-id this)]
    (when cid
      (when on-container-id
        (on-container-id cid))
      (gui/unregister-container-by-id! cid)))
  (gui/unregister-menu-container! this)
  (gui/safe-close! clj-container)
  (gui/unregister-active-container! clj-container)
  (gui/unregister-player-container! player)
  (when call-super-removed?
    (let [^CMenuBridge s this]
      (.callSuperRemoved s player)))
  (log/info log-message (str player)))

(defn- broadcast-and-sync!
  [this clj-container before-super-broadcast!]
  (when before-super-broadcast!
    (before-super-broadcast!))
  (let [^CMenuBridge s this]
    (.callSuperBroadcastChanges s))
  (gui/safe-sync! clj-container))

(defn- quick-move-stack
  [this clj-container slot-index error-prefix]
  (try
    (let [^Slot slot (.getSlot ^AbstractContainerMenu this slot-index)]
      (if (and slot (.hasItem slot))
        (let [^ItemStack stack (.getItem slot)]
          (gui/execute-quick-move-forge this clj-container slot-index slot stack))
        ItemStack/EMPTY))
    (catch Exception e
      (log/error error-prefix (.getMessage e))
      ItemStack/EMPTY)))

(defn- finalize-menu-registration!
  [menu window-id clj-container player]
  (gui/register-active-container! clj-container)
  (when player
    (gui/register-player-container! player clj-container))
  (gui/register-menu-container! menu clj-container)
  (gui/register-container-by-id! window-id clj-container)
  menu)

(defn platform-menu-proxy-opts
  "Return shared CMenuBridge options for a loader platform key."
  ([platform-key]
   (platform-menu-proxy-opts platform-key nil))
  ([platform-key opts]
   (merge
    {:get-slot-layout gui/get-slot-layout
     :default-player-inventory-mode :full
     :call-super-removed? false
     :remove-log-message "Menu closed for player"
     :quick-move-error-prefix "Error in quickMoveStack:"}
    (case platform-key
      :fabric-1.20.1 {:call-super-removed? true
                      :remove-log-message "Fabric menu closed for player"
                      :quick-move-error-prefix "Error in Fabric quickMoveStack:"}
      :forge-1.20.1 {}
      {})
    opts)))

(defn create-menu-proxy
  "Create a CMenuBridge proxy around a Clojure container.

  Optional opts:
  - :get-slot-layout          fn [gui-id] -> layout map (default gui/get-slot-layout)
  - :default-player-inventory-mode  :full/:hotbar-only/:none (default :full)
  - :player                   player owning this menu, when available
  - :call-super-removed?      boolean
  - :remove-log-message       string
  - :quick-move-error-prefix  string"
  [window-id menu-type clj-container {:keys [get-slot-layout
                                             default-player-inventory-mode
                                             player
                                             call-super-removed?
                                             remove-log-message
                                             quick-move-error-prefix]
                                      :or {get-slot-layout gui/get-slot-layout
                                           default-player-inventory-mode :full
                                           call-super-removed? false
                                           remove-log-message "Menu closed for player"
                                           quick-move-error-prefix "Error in quickMoveStack:"}}]
  (let [tab-slot (when (tabbed/tabbed-container? clj-container)
                   (tabbed-slots/create-tab-data-slot clj-container))
        menu (proxy [CMenuBridge] [menu-type (int window-id)]
               (stillValid [player]
                 (boolean (gui/safe-validate clj-container player)))

               (removed [player]
                 (remove-menu!
                  this
                  clj-container
                  player
                  {:on-container-id tabbed/clear-tab-index-by-container-id!
                   :call-super-removed? call-super-removed?
                   :log-message remove-log-message}))

               (broadcastChanges []
                 (broadcast-and-sync!
                  this
                  clj-container
                  (fn []
                    (tabbed-slots/sync-tab-slot-from-container! tab-slot clj-container)
                    (slots-sync/sync-data-slots-from-container! clj-container))))

               (clicked [slot-index button click-type player]
                 (when (or (not (tabbed/tabbed-container? clj-container))
                           (tabbed/slots-active-for-menu? this clj-container))
                   (let [^CMenuBridge s this]
                     (.callSuperClicked s slot-index button click-type player))))

               (quickMoveStack [player slot-index]
                 (if (and (tabbed/tabbed-container? clj-container)
                          (not (tabbed/slots-active-for-menu? this clj-container)))
                   ItemStack/EMPTY
                   (quick-move-stack this clj-container slot-index quick-move-error-prefix)))

               (canTakeItemForPickAll [_stack _slot] true)
               (canDragTo [_slot] true))]
    (slots-sync/setup-menu-slots! menu clj-container tab-slot {:get-slot-layout get-slot-layout
                                                               :default-player-inventory-mode default-player-inventory-mode})
    (finalize-menu-registration! menu window-id clj-container player)))

(defn create-platform-menu-proxy
  "Create a menu proxy with loader-specific shared options."
  [platform-key window-id menu-type clj-container opts]
  (create-menu-proxy
   window-id
   menu-type
   clj-container
   (platform-menu-proxy-opts platform-key opts)))

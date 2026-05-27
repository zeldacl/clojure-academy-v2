(ns cn.li.mc1201.gui.menu.proxy
  "Shared CMenuBridge proxy builder for platform adapters.

  Platform namespaces provide small callback/config differences (slot add callback,
  loader-specific remove semantics, player inventory mode defaults), while all menu
  proxy and slot/data-slot sync logic lives here."
  (:require [cn.li.mcmod.gui.registry-core :as gui]
            [cn.li.mcmod.gui.tabbed-gui :as tabbed]
            [cn.li.mcmod.gui.container.schema :as container-schema]
            [cn.li.mc1201.gui.slots.tabbed :as tabbed-slots]
            [cn.li.mc1201.gui.slots.sync :as slots-sync]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.mc1201.gui CMenuBridge]
           [net.minecraft.server.level ServerPlayer]
           [net.minecraft.world.entity.player Player]
           [net.minecraft.world.inventory AbstractContainerMenu Slot]
           [net.minecraft.world.item ItemStack]))

(defn- owner-for-player
  [^Player player]
  (let [player-uuid (some-> player .getUUID str)
        ^ServerPlayer server-player (when (instance? ServerPlayer player) player)
        server-session-id (try
                            (when-let [server (some-> server-player .getServer)]
                              [:server (System/identityHashCode server)])
                            (catch Throwable _ nil))
        client-session-id (or runtime-hooks/*client-session-id*
                              (when (and player (nil? server-session-id))
                                [:client-player (System/identityHashCode player)]))]
    (cond
      (and server-session-id player-uuid)
      {:server-session-id server-session-id
       :player-uuid player-uuid
       :player player}

      (and client-session-id player-uuid)
      {:client-session-id client-session-id
       :player-uuid player-uuid
       :player player}

      :else
      (throw (ex-info "GUI menu owner requires session id and player UUID"
                      {:player player
                       :server-session-id server-session-id
                       :client-session-id client-session-id
                       :player-uuid player-uuid})))))

(defn- enrich-container-owner
  [clj-container owner]
  (cond-> (assoc clj-container :owner owner)
    (:server-session-id owner) (assoc :server-session-id (:server-session-id owner))
    (:client-session-id owner) (assoc :client-session-id (:client-session-id owner))
    (:player-uuid owner) (assoc :player-uuid (:player-uuid owner))
    (:player owner) (assoc :player (:player owner))))

(defn- remove-menu!
  [this clj-container player {:keys [on-container-id
                                     call-super-removed?
                                     log-message]
                              :or {call-super-removed? false
                                   log-message "Menu closed for player"}}]
  (let [owner (or (:owner clj-container) (owner-for-player player))
        cid (gui/get-menu-container-id this)]
    (when cid
      (when on-container-id
        (on-container-id owner cid))
      (gui/unregister-container-by-id! owner cid)))
  (gui/unregister-menu-container! this)
  (gui/safe-close! clj-container)
  (let [owner (or (:owner clj-container) (owner-for-player player))]
    (gui/unregister-active-container! owner clj-container)
    (gui/unregister-player-container! owner clj-container))
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
  (let [sync-get (:sync-get clj-container)
        last-sent (:sync-last-sent clj-container)
        has-sent? (:sync-has-sent? clj-container)]
    (if (and sync-get last-sent has-sent?)
      (let [payload (sync-get clj-container)]
        (when (container-schema/sync-payload-dirty? last-sent has-sent? payload)
          (container-schema/cache-sync-payload! last-sent has-sent? payload)
          (gui/safe-sync! clj-container)))
      (gui/safe-sync! clj-container))))

(defn- quick-move-stack
  [this clj-container player slot-index error-prefix]
  (try
    (let [^Slot slot (.getSlot ^AbstractContainerMenu this slot-index)]
      (if (and slot (.hasItem slot))
        (let [^ItemStack stack (.getItem slot)
              moved (.copy stack)
              gui-id (gui/get-gui-id-for-container clj-container)
              [tile-start tile-end] (if gui-id
                                      (gui/get-slot-range gui-id :tile)
                                      [0 -1])
              [player-main-start player-main-end] (if gui-id
                                                    (gui/get-slot-range gui-id :player-main)
                                                    [0 -1])
              [player-hotbar-start player-hotbar-end] (if gui-id
                                                        (gui/get-slot-range gui-id :player-hotbar)
                                                        [0 -1])
              player-start (if (and (<= 0 player-main-start) (<= 0 player-hotbar-start))
                             (min player-main-start player-hotbar-start)
                             player-main-start)
              player-end (max player-main-end player-hotbar-end)
              tile-end-exclusive (inc tile-end)
              player-end-exclusive (inc player-end)
              moved? (cond
                       (and (<= tile-start slot-index) (<= slot-index tile-end)
                            (<= 0 player-start) (<= player-start player-end))
                       (.callSuperMoveItemStackTo ^CMenuBridge this stack player-start player-end-exclusive true)

                       (and (<= player-start slot-index) (<= slot-index player-end)
                            (<= 0 tile-start) (<= tile-start tile-end))
                       (.callSuperMoveItemStackTo ^CMenuBridge this stack tile-start tile-end-exclusive false)

                       :else false)]
          (if-not moved?
            ItemStack/EMPTY
            (if (= (.getCount stack) (.getCount moved))
              ItemStack/EMPTY
              (do
                (if (.isEmpty stack)
                  (.setByPlayer slot ItemStack/EMPTY)
                  (.setChanged slot))
                (.onTake slot player stack)
                moved))))
        ItemStack/EMPTY))
    (catch Exception e
      (log/error error-prefix (.getMessage e))
      ItemStack/EMPTY)))

(defn- finalize-menu-registration!
  [menu window-id clj-container owner]
  (gui/register-active-container! owner clj-container)
  (gui/register-player-container! owner clj-container)
  (gui/register-menu-container! menu clj-container)
  (gui/register-container-by-id! owner window-id clj-container)
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

(defn quick-move-allowed?
  "Return true when quick-move/shift-click is allowed for the current tab state."
  [menu clj-container]
  (or (not (tabbed/tabbed-container? clj-container))
      (tabbed/slots-active-for-menu? menu clj-container)))

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
  (let [owner (owner-for-player player)
        clj-container (enrich-container-owner clj-container owner)
        tab-slot (when (tabbed/tabbed-container? clj-container)
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
                 (if-not (quick-move-allowed? this clj-container)
                   ItemStack/EMPTY
                   (quick-move-stack this clj-container player slot-index quick-move-error-prefix)))

               (canTakeItemForPickAll [_stack _slot] true)
               (canDragTo [_slot] true))]
    (slots-sync/setup-menu-slots! menu clj-container tab-slot {:get-slot-layout get-slot-layout
                                                               :default-player-inventory-mode default-player-inventory-mode})
    (finalize-menu-registration! menu window-id clj-container owner)))

(defn create-platform-menu-proxy
  "Create a menu proxy with loader-specific shared options."
  [platform-key window-id menu-type clj-container opts]
  (create-menu-proxy
   window-id
   menu-type
   clj-container
   (platform-menu-proxy-opts platform-key opts)))

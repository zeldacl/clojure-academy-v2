(ns cn.li.forge1201.gui.menu-bridge
  "Forge 1.20.1 AbstractContainerMenu wrapper.

  Uses runtime `proxy` instead of `gen-class` because gen-class is a
  compile-time-only macro (guarded by *compile-files*) and produces no class
  when Clojure source files are loaded dynamically without AOT compilation.
  Tabbed GUIs: same UI, same container; :tab-index is only the 'current tab' state.
  When container has :tab-index, we add DataSlot + conditional slots (slots active only when tab-index is 0)."
  (:require [cn.li.mcmod.gui.adapter :as gui]
            [cn.li.mcmod.gui.tabbed-gui :as tabbed]
            [cn.li.forge1201.gui.slots :as slots]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.world.inventory AbstractContainerMenu DataSlot]
           [net.minecraft.world.item ItemStack]
           [net.minecraft.world Container]))

(defn- create-tile-inventory-adapter
  "Expose Clojure container slot access as a vanilla `Container` so Forge Slot
   rendering/interaction can work without Java container classes."
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

(def tab-data-slot-index 0)

(defn get-tab-index-from-menu
  "Read tab index from menu's first DataSlot (for tabbed GUIs). Returns nil if not tabbed or read fails."
  [^AbstractContainerMenu menu]
  (when (and menu (pos? (.getDataSlotCount menu)))
    (try
      (let [slot (.getDataSlot menu (int tab-data-slot-index))]
        (when slot (.get ^net.minecraft.world.inventory.DataSlot slot)))
      (catch Exception _ nil))))

(defn- create-tab-data-slot
  "Create a standalone DataSlot and sync from container's :tab-index.
   Avoids proxy (which breaks for DataSlot.get() due to 0-param Java method / arity mismatch)."
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
  [^AbstractContainerMenu menu clj-container tab-slot]
  (let [gui-id (gui/get-gui-id-for-container clj-container)
        player (:player clj-container)
        player-inventory (when player
                           (.getInventory player))
        tile-inventory (create-tile-inventory-adapter clj-container)
        tabbed? (tabbed/tabbed-container? clj-container)
        active?-fn (when tabbed? (fn [] (tabbed/slots-active? clj-container)))]
    (when tab-slot
      (.addDataSlot menu tab-slot))
    (when-let [data-slots (:data-slots clj-container)]
      (doseq [^DataSlot data-slot (vals data-slots)]
        (.addDataSlot menu data-slot)))
    (when (and gui-id player-inventory)
      ;; Offsets aligned with AcademyCraft TechUIContainer: tile at (0,0) => schema coords are absolute; player inv at (6, 105) => hotbar at 163
      (slots/add-gui-slots menu tile-inventory gui-id 0 0 (when tabbed? active?-fn))
      (slots/add-player-inventory-slots menu player-inventory 6 105 (when tabbed? active?-fn)))))

(defn create-menu-bridge
  "Create an AbstractContainerMenu proxy wrapping a Clojure container.

  proxy generates the implementing class at runtime inside the current
  DynamicClassLoader, so it works without AOT compilation.

  Args:
  - window-id:     int        - Forge menu container id
  - menu-type:     MenuType   - registered MenuType for this GUI
  - clj-container: map        - Clojure-side container (NodeContainer, etc.)"
  [window-id menu-type clj-container]
  (let [tab-slot (when (tabbed/tabbed-container? clj-container)
                   (create-tab-data-slot clj-container))
        tab-idx-ref (atom (int (or (when (and (tabbed/tabbed-container? clj-container)
                                              (:tab-index clj-container))
                                     @(:tab-index clj-container))
                                   0)))
        menu
        (proxy [AbstractContainerMenu] [menu-type (int window-id)]
          (stillValid [player]
            (gui/safe-validate clj-container player))

          (removed [player]
            (let [cid (gui/get-menu-container-id this)]
              (when cid
                (tabbed/clear-tab-index-by-container-id! cid)
                (gui/unregister-container-by-id! cid)))
            (gui/unregister-menu-container! this)
            (proxy-super removed player)
            (gui/safe-close! clj-container)
            (gui/unregister-active-container! clj-container)
            (gui/unregister-player-container! player)
            (log/info "Menu closed for player" (.getName player)))

          (broadcastChanges []
            (when (and (tabbed/tabbed-container? clj-container) (:tab-index clj-container))
              (reset! tab-idx-ref (int @(:tab-index clj-container))))
            (sync-tab-slot-from-container! tab-slot clj-container)
            (sync-data-slots-from-container! clj-container)
            (proxy-super broadcastChanges)
            (gui/safe-sync! clj-container))

          ;; clicked() runs only on the server when it receives click packets.
          (clicked [slot-index button click-type player]
            (when (or (not (tabbed/tabbed-container? clj-container))
                      (tabbed/slots-active-for-menu? this clj-container))
              (proxy-super clicked slot-index button click-type player)))

          (quickMoveStack [player slot-index]
            (if (and (tabbed/tabbed-container? clj-container)
                     (not (tabbed/slots-active-for-menu? this clj-container)))
              ItemStack/EMPTY
              (try
                (let [slot (.getSlot this slot-index)]
                  (if (and slot (.hasItem slot))
                    (let [stack (.getItem slot)]
                      (gui/execute-quick-move-forge this clj-container slot-index slot stack))
                    ItemStack/EMPTY))
                (catch Exception e
                  (log/error "Error in quickMoveStack:" (.getMessage e))
                  ItemStack/EMPTY))))

          (canTakeItemForPickAll [stack slot] true)
          (canDragTo [slot] true))]
    (setup-menu-slots! menu clj-container tab-slot)
    (gui/register-menu-container! menu clj-container)
    (gui/register-container-by-id! window-id clj-container)
    menu))
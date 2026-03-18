(ns my-mod.forge1201.gui.menu-bridge
  "Forge 1.20.1 AbstractContainerMenu wrapper.

  Uses runtime `proxy` instead of `gen-class` because gen-class is a
  compile-time-only macro (guarded by *compile-files*) and produces no class
  when Clojure source files are loaded dynamically without AOT compilation.
  Tabbed GUIs: same UI, same container; :tab-index is only the 'current tab' state.
  When container has :tab-index, we add DataSlot + conditional slots (slots active only when tab-index is 0)."
  (:require [my-mod.gui.platform-adapter :as gui]
            [my-mod.gui.tabbed-gui :as tabbed]
            [my-mod.forge1201.gui.slots :as slots]
            [my-mod.util.log :as log])
  (:import [my_mod.forge1201.gui ACContainerMenu]
           [net.minecraft.world.inventory AbstractContainerMenu DataSlot]
           [net.minecraft.world.inventory ClickType]
           [net.minecraft.world.inventory Slot]
           [net.minecraft.world.item ItemStack]
           [net.minecraft.world Container]
           [net.minecraft.world.entity.player Player]))

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

(defn- setup-menu-slots!
  [^AbstractContainerMenu menu clj-container tab-slot]
  (let [gui-id (gui/get-gui-id-for-container clj-container)
        player (:player clj-container)
        player-inventory (when player
                           (clojure.lang.Reflector/invokeInstanceMethod player "getInventory" (object-array [])))
        tile-inventory (create-tile-inventory-adapter clj-container)
        tabbed? (tabbed/tabbed-container? clj-container)
        active?-fn (when tabbed? (fn [] (tabbed/slots-active? clj-container)))]
    (when tab-slot
      (when (instance? ACContainerMenu menu)
        (.publicAddDataSlot ^ACContainerMenu menu tab-slot)))
    (when (and gui-id player-inventory)
      ;; Offsets aligned with AcademyCraft TechUIContainer: tile at (0,0) => schema coords are absolute; player inv at (6, 105) => hotbar at 163
      (slots/add-gui-slots menu tile-inventory gui-id 0 0 (when tabbed? active?-fn))
      (slots/add-player-inventory-slots menu player-inventory 6 105 (when tabbed? active?-fn)))))

(defn create-menu-bridge
  "Create an AbstractContainerMenu proxy wrapping a Clojure container.

  proxy generates the implementing class at runtime inside the current
  DynamicClassLoader, so it works without AOT compilation.

  Args:
  - window-id:     int        — Forge menu container id
  - menu-type:     MenuType   — registered MenuType for this GUI
  - clj-container: map        — Clojure-side container (NodeContainer, etc.)"
  [window-id menu-type clj-container]
  (let [tab-slot (when (tabbed/tabbed-container? clj-container)
                   (create-tab-data-slot clj-container))
        ;; Ref updated every tick from container so clicked/quickMoveStack see latest tab
        tab-idx-ref (atom (int (or (when (and (tabbed/tabbed-container? clj-container) (:tab-index clj-container))
                                    @(:tab-index clj-container))
                                  0)))
        menu
        (proxy [ACContainerMenu] [menu-type (int window-id)]

          (stillValid [player]
            (gui/safe-validate clj-container player))

          (removed [^Player player]
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
            (proxy-super broadcastChanges)
            (gui/safe-sync! clj-container))

          ;; Block all slot clicks when not on inv-window tab; use tab by container-id so client menu sees correct tab.
          ;; clicked() runs only on the SERVER when the server receives a slot-click packet. If the client blocks the
          ;; click (Screen mouseClicked/slotClicked/findSlot), no packet is sent and this is never called.
          (clicked [slot-index button ^ClickType click-type ^Player player]
            (when (or (not (tabbed/tabbed-container? clj-container))
                      (tabbed/slots-active-for-menu? this clj-container))
              (proxy-super clicked (int slot-index) (int button) click-type player)))

          (quickMoveStack [player slot-index]
            (if (and (tabbed/tabbed-container? clj-container)
                     (not (tabbed/slots-active-for-menu? this clj-container)))
              ItemStack/EMPTY
              (try
                (let [^Slot slot (.getSlot this (int slot-index))]
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

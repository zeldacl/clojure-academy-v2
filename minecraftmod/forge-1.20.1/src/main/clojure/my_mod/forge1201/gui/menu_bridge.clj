(ns my-mod.forge1201.gui.menu-bridge
  "Forge 1.20.1 AbstractContainerMenu wrapper.

  Uses runtime `proxy` instead of `gen-class` because gen-class is a
  compile-time-only macro (guarded by *compile-files*) and produces no class
  when Clojure source files are loaded dynamically without AOT compilation."
  (:require [my-mod.gui.platform-adapter :as gui]
            [my-mod.forge1201.gui.slots :as slots]
            [my-mod.util.log :as log])
  (:import [net.minecraft.world.inventory AbstractContainerMenu]
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

(defn- setup-menu-slots!
  [^AbstractContainerMenu menu clj-container]
  (let [gui-id (gui/get-gui-id-for-container clj-container)
        player (:player clj-container)
        player-inventory (when player
                           (clojure.lang.Reflector/invokeInstanceMethod player "getInventory" (object-array [])))
        tile-inventory (create-tile-inventory-adapter clj-container)]
    (when (and gui-id player-inventory)
      ;; Tile/gui slots are defined by slot schema coordinates.
      (slots/add-gui-slots menu tile-inventory gui-id 8 84)
      ;; Standard player inventory placement for 176x187 style GUIs.
      (slots/add-player-inventory-slots menu player-inventory 8 107))))

(defn create-menu-bridge
  "Create an AbstractContainerMenu proxy wrapping a Clojure container.

  proxy generates the implementing class at runtime inside the current
  DynamicClassLoader, so it works without AOT compilation.

  Args:
  - window-id:     int        — Forge menu container id
  - menu-type:     MenuType   — registered MenuType for this GUI
  - clj-container: map        — Clojure-side container (NodeContainer, etc.)"
  [window-id menu-type clj-container]
  (let [menu
        (proxy [AbstractContainerMenu] [menu-type (int window-id)]

          (stillValid [player]
            (gui/safe-validate clj-container player))

          (removed [player]
            (proxy-super removed player)
            (gui/safe-close! clj-container)
            (gui/unregister-active-container! clj-container)
            (gui/unregister-player-container! player)
            (log/info "Menu closed for player" (.getName player)))

          (broadcastChanges []
            (proxy-super broadcastChanges)
            (gui/safe-sync! clj-container))

          (quickMoveStack [player slot-index]
            (try
              (let [slot (.getSlot this slot-index)]
                (if (and slot (.hasItem slot))
                  (let [stack (.getItem slot)]
                    (gui/execute-quick-move-forge this clj-container slot-index slot stack))
                  ItemStack/EMPTY))
              (catch Exception e
                (log/error "Error in quickMoveStack:" (.getMessage e))
                ItemStack/EMPTY)))

          (canTakeItemForPickAll [stack slot] true)
          (canDragTo [slot] true))]
    (setup-menu-slots! menu clj-container)
    menu))

(ns my-mod.forge1201.gui.slots
  "Forge 1.20.1 GUI Slot Implementations

  Uses runtime `proxy` instead of `gen-class` for each custom slot type.
  gen-class is a compile-time-only macro (guarded by *compile-files*) and
  produces no class when Clojure source files are loaded without AOT."
  (:require [my-mod.gui.platform-adapter :as gui]
            [my-mod.gui.slot-validators :as slot-validators]
            [my-mod.util.log :as log])
  (:import [net.minecraft.world.inventory Slot]))

;; ============================================================================
;; Slot Factory Functions
;; ============================================================================

(defn create-energy-slot
  "Create a slot that only accepts energy items."
  [inventory slot-index x y]
  (proxy [Slot] [inventory (int slot-index) (int x) (int y)]
    (mayPlace [stack]
      (slot-validators/energy-item-validator stack))
    (getMaxStackSize [] 1)))

(defn create-plate-slot
  "Create a slot that filters for constraint-plate items."
  [inventory slot-index x y]
  (proxy [Slot] [inventory (int slot-index) (int x) (int y)]
    (mayPlace [stack]
      (slot-validators/constraint-plate-validator stack))
    (getMaxStackSize [] 1)))

(defn create-core-slot
  "Create a slot that filters for mat-core items."
  [inventory slot-index x y]
  (proxy [Slot] [inventory (int slot-index) (int x) (int y)]
    (mayPlace [stack]
      (slot-validators/matrix-core-validator stack))
    (getMaxStackSize [] 1)))

(defn create-output-slot
  "Create an output-only slot (no insertion allowed)."
  [inventory slot-index x y]
  (proxy [Slot] [inventory (int slot-index) (int x) (int y)]
    (mayPlace [_stack] false)
    (mayPickup [_player] true)))

(defn create-standard-slot
  "Create a standard slot (accepts any item)."
  [inventory slot-index x y]
  (Slot. inventory (int slot-index) (int x) (int y)))

;; ============================================================================
;; Slot Layout Helpers
;; ============================================================================

(defn add-player-inventory-slots
  "Add standard player inventory slots (3x9 grid + hotbar).

  Args:
  - container:        AbstractContainerMenu to add slots to
  - player-inventory: Inventory
  - x-offset:         int (left edge x position)
  - y-offset:         int (top edge y position for main inventory)

  Side effects: Adds 36 slots to container"
  [container player-inventory x-offset y-offset]
  ;; Main inventory (3 rows x 9 columns)
  (doseq [row (range 3)
          col (range 9)]
    (let [slot-index (+ (* row 9) col 9)
          x (+ x-offset (* col 18))
          y (+ y-offset (* row 18))]
      (.addSlot container (create-standard-slot player-inventory slot-index x y))))
  ;; Hotbar (1 row x 9 columns)
  (doseq [col (range 9)]
    (let [slot-index col
          x (+ x-offset (* col 18))
          y (+ y-offset 58)]
      (.addSlot container (create-standard-slot player-inventory slot-index x y)))))

(defn add-gui-slots
  "Add GUI-specific slots based on metadata-driven layout.

  Platform-agnostic: reads slot layout from gui-metadata and creates
  appropriate slot types dynamically.

  Args:
  - container:  AbstractContainerMenu to add slots to
  - inventory:  Container for the GUI
  - gui-id:     int (GUI identifier)
  - x-offset:   int (base x position)
  - y-offset:   int (base y position)

  Side effects: Adds slots to container based on layout"
  [container inventory gui-id x-offset y-offset]
  (when-let [layout (gui/get-slot-layout gui-id)]
    (doseq [slot-def (:slots layout)]
      (let [{:keys [type index x y]} slot-def
            abs-x (+ x-offset x)
            abs-y (+ y-offset y)
            slot  (case type
                    :energy (create-energy-slot  inventory index abs-x abs-y)
                    :plate  (create-plate-slot   inventory index abs-x abs-y)
                    :core   (create-core-slot    inventory index abs-x abs-y)
                    :output (create-output-slot  inventory index abs-x abs-y)
                    (create-standard-slot        inventory index abs-x abs-y))]
        (.addSlot container slot)))))

;; ============================================================================
;; Slot Index Helpers
;; ============================================================================

(defn get-slot-range
  "Get slot index range for an inventory section.

  Args:
  - gui-id:   int (GUI identifier)
  - section:  :tile, :player-main, or :player-hotbar

  Returns: [start-index end-index] (inclusive)"
  [gui-id section]
  (gui/get-slot-range gui-id section))

(defn slot-in-range?
  "Check if slot index falls within a given inventory section.

  Args:
  - slot-index: int
  - gui-id:     int (GUI identifier)
  - section:    :tile, :player-main, or :player-hotbar

  Returns: boolean"
  [slot-index gui-id section]
  (let [[start end] (get-slot-range gui-id section)]
    (<= start slot-index end)))

;; ============================================================================
;; Debugging Helpers
;; ============================================================================

(defn log-slot-contents
  "Log all slot contents for debugging."
  [container]
  (log/debug "Container slots:")
  (doseq [i (range (.slots container))]
    (let [slot  (.getSlot container i)
          stack (.getItem slot)]
      (when-not (.isEmpty stack)
        (log/debug "  Slot" i ":"
                   (.getCount stack) "x"
                   (-> stack .getItem .toString))))))

(defn validate-slot-setup
  "Validate that container has the expected number of slots.

  Args:
  - container:      AbstractContainerMenu
  - expected-count: int

  Returns: boolean"
  [container expected-count]
  (let [actual-count (count (.slots container))]
    (if (= actual-count expected-count)
      (do (log/info "Slot validation passed:" actual-count "slots") true)
      (do (log/error "Slot validation failed: expected" expected-count
                     "but got" actual-count) false))))

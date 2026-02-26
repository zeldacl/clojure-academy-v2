(ns my-mod.forge1201.gui.slots
  "Forge 1.20.1 GUI Slot Implementations
  
  Platform-agnostic design: Uses gui-metadata for slot layouts,
  eliminating hardcoded game concepts (Node, Matrix, etc.)."
  (:require [my-mod.gui.platform-adapter :as gui]
            [my-mod.gui.slot-validators :as slot-validators]
            [my-mod.util.log :as log])
  (:import [net.minecraft.world.inventory Slot]
           [net.minecraft.world.entity.player Player]
           [net.minecraft.world.item ItemStack]
           [net.minecraft.world.Container]))

;; ============================================================================
;; Custom Slot: Energy Item Only
;; ============================================================================

(gen-class
  :name my_mod.forge1201.gui.SlotEnergyItem
  :extends net.minecraft.world.inventory.Slot
  :constructors {[net.minecraft.world.Container int int int]
                 [net.minecraft.world.Container int int int]}
  :state state
  :init init)

(defn -init
  "Initialize energy item slot"
  [inventory slot-index x y]
  [[inventory slot-index x y] {}])

(defn -mayPlace
  "Only allow energy items in this slot"
  [this stack]
  (slot-validators/energy-item-validator stack))

(defn -getMaxStackSize
  "Energy items usually don't stack, but allow it if they do"
  [this]
  1)

;; ============================================================================
;; Custom Slot: Filtered Plate Items
;; ============================================================================

(gen-class
  :name my_mod.forge1201.gui.SlotFilteredPlate
  :extends net.minecraft.world.inventory.Slot
  :constructors {[net.minecraft.world.Container int int int]
                 [net.minecraft.world.Container int int int]}
  :state state
  :init init-plate)

(defn -init-plate
  "Initialize filtered plate slot"
  [inventory slot-index x y]
  [[inventory slot-index x y] {}])

(defn -mayPlace
  "Only allow plate-type items in this slot"
  [this stack]
  (slot-validators/constraint-plate-validator stack))

(defn -getMaxStackSize
  "Plate items limited to single stack"
  [this]
  1)

;; ============================================================================
;; Custom Slot: Filtered Core Items
;; ============================================================================

(gen-class
  :name my_mod.forge1201.gui.SlotFilteredCore
  :extends net.minecraft.world.inventory.Slot
  :constructors {[net.minecraft.world.Container int int int]
                 [net.minecraft.world.Container int int int]}
  :state state
  :init init-core)

(defn -init-core
  "Initialize filtered core slot"
  [inventory slot-index x y]
  [[inventory slot-index x y] {}])

(defn -mayPlace
  "Only allow core-type items in this slot"
  [this stack]
  (slot-validators/matrix-core-validator stack))

(defn -getMaxStackSize
  "Core items never stack"
  [this]
  1)

;; ============================================================================
;; Custom Slot: Output Only (No Insertion)
;; ============================================================================

(gen-class
  :name my_mod.forge1201.gui.SlotOutput
  :extends net.minecraft.world.inventory.Slot
  :constructors {[net.minecraft.world.Container int int int]
                 [net.minecraft.world.Container int int int]}
  :state state
  :init init-output)

(defn -init-output
  "Initialize output-only slot"
  [inventory slot-index x y]
  [[inventory slot-index x y] {}])

(defn -mayPlace
  "Never allow direct insertion into output slot"
  [this stack]
  (slot-validators/output-slot-validator stack))

(defn -mayPickup
  "Allow taking items from output slot"
  [this player]
  true)

;; ============================================================================
;; Slot Factory Functions
;; ============================================================================

(defn create-energy-slot
  "Create a slot that only accepts energy items
  
  Args:
  - inventory: Container
  - slot-index: int (slot index in inventory)
  - x: int (GUI x position)
  - y: int (GUI y position)
  
  Returns: Slot instance"
  [inventory slot-index x y]
  (my_mod.forge1201.gui.SlotEnergyItem. inventory slot-index x y))

(defn create-plate-slot
  "Create a slot that filters for plate-type items"
  [inventory slot-index x y]
  (my_mod.forge1201.gui.SlotFilteredPlate. inventory slot-index x y))

(defn create-core-slot
  "Create a slot that filters for core-type items"
  [inventory slot-index x y]
  (my_mod.forge1201.gui.SlotFilteredCore. inventory slot-index x y))

(defn create-output-slot
  "Create an output-only slot (no insertion allowed)"
  [inventory slot-index x y]
  (my_mod.forge1201.gui.SlotOutput. inventory slot-index x y))

(defn create-standard-slot
  "Create a standard slot (accepts any item)"
  [inventory slot-index x y]
  (Slot. inventory slot-index x y))

;; ============================================================================
;; Slot Layout Helpers
;; ============================================================================

(defn add-player-inventory-slots
  "Add standard player inventory slots (3x9 grid + hotbar)
  
  Args:
  - container: AbstractContainerMenu to add slots to
  - player-inventory: Inventory
  - x-offset: int (left edge x position)
  - y-offset: int (top edge y position for main inventory)
  
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
  "Add GUI-specific slots based on metadata-driven layout
  
  Platform-agnostic implementation: Reads slot layout from gui-metadata
  and creates appropriate slot types dynamically.
  
  Args:
  - container: AbstractContainerMenu to add slots to
  - inventory: Container for the GUI
  - gui-id: int (GUI identifier)
  - x-offset: int (base x position)
  - y-offset: int (base y position)
  
  Side effects: Adds slots to container based on layout"
  [container inventory gui-id x-offset y-offset]
  (when-let [layout (gui/get-slot-layout gui-id)]
    (doseq [slot-def (:slots layout)]
      (let [{:keys [type index x y]} slot-def
            abs-x (+ x-offset x)
            abs-y (+ y-offset y)
            slot (case type
                   :energy (create-energy-slot inventory index abs-x abs-y)
                   :plate (create-plate-slot inventory index abs-x abs-y)
                   :core (create-core-slot inventory index abs-x abs-y)
                   :output (create-output-slot inventory index abs-x abs-y)
                   (create-standard-slot inventory index abs-x abs-y))]
        (.addSlot container slot)))))

;; ============================================================================
;; Slot Index Helpers
;; ============================================================================

(defn get-slot-range
  "Get slot index range for different inventory sections
  
  Platform-agnostic implementation: Delegates to gui-metadata.
  
  Args:
  - gui-id: int (GUI identifier)
  - section: :tile, :player-main, :player-hotbar
  
  Returns: [start-index end-index] (inclusive)"
  [gui-id section]
  (gui/get-slot-range gui-id section))

(defn slot-in-range?
  "Check if slot index is in given section
  
  Args:
  - slot-index: int
  - gui-id: int (GUI identifier)
  - section: :tile, :player-main, :player-hotbar
  
  Returns: boolean"
  [slot-index gui-id section]
  (let [[start end] (get-slot-range gui-id section)]
    (<= start slot-index end)))

;; ============================================================================
;; Debugging Helpers
;; ============================================================================

(defn log-slot-contents
  "Log all slot contents for debugging"
  [container]
  (log/debug "Container slots:")
  (doseq [i (range (.slots container))]
    (let [slot (.getSlot container i)
          stack (.getItem slot)]
      (when-not (.isEmpty stack)
        (log/debug "  Slot" i ":" 
                   (.getCount stack) "x" 
                   (-> stack .getItem .toString))))))

(defn validate-slot-setup
  "Validate that container slots are set up correctly
  
  Args:
  - container: AbstractContainerMenu
  - expected-count: int
  
  Returns: boolean"
  [container expected-count]
  (let [actual-count (count (.slots container))]
    (if (= actual-count expected-count)
      (do
        (log/info "Slot validation passed:" actual-count "slots")
        true)
      (do
        (log/error "Slot validation failed: expected" expected-count 
                   "but got" actual-count)
        false))))

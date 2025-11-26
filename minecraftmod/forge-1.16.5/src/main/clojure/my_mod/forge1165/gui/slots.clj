(ns my-mod.forge1165.gui.slots
  "Forge 1.16.5 GUI Slot Implementations
  
  Platform-agnostic design: Uses gui-metadata for slot layouts,
  eliminating hardcoded game concepts (Node, Matrix, etc.)."
  (:require [my-mod.wireless.gui.gui-metadata :as gui-metadata]
            [my-mod.energy.stub :as energy]
            [my-mod.item.constraint-plate :as plate]
            [my-mod.item.mat-core :as core]
            [my-mod.util.log :as log])
  (:import [net.minecraft.inventory.container Slot]
           [net.minecraft.inventory IInventory]
           [net.minecraft.item.ItemStack]
           [net.minecraft.entity.player PlayerEntity]))

;; ============================================================================
;; Custom Slot: Energy Item Only
;; ============================================================================

(gen-class
  :name my_mod.forge1165.gui.SlotEnergyItem
  :extends net.minecraft.inventory.container.Slot
  :constructors {[net.minecraft.inventory.IInventory int int int] 
                 [net.minecraft.inventory.IInventory int int int]}
  :state state
  :init init)

(defn -init
  "Initialize energy item slot"
  [inventory slot-index x y]
  [[inventory slot-index x y] {}])

(defn -isItemValid
  "Only allow energy items in this slot"
  [this stack]
  (energy/is-energy-item-supported? stack))

(defn -getMaxStackSize
  "Energy items usually don't stack, but allow it if they do"
  [this]
  1)

;; ============================================================================
;; Custom Slot: Filtered Plate Items
;; ============================================================================

(gen-class
  :name my_mod.forge1165.gui.SlotFilteredPlate
  :extends net.minecraft.inventory.container.Slot
  :constructors {[net.minecraft.inventory.IInventory int int int] 
                 [net.minecraft.inventory.IInventory int int int]}
  :state state
  :init init-plate)

(defn -init-plate
  "Initialize filtered plate slot"
  [inventory slot-index x y]
  [[inventory slot-index x y] {}])

(defn -isItemValid
  "Only allow plate-type items in this slot"
  [this stack]
  (plate/is-constraint-plate? stack))

(defn -getMaxStackSize
  "Plate items limited to single stack"
  [this]
  1)

;; ============================================================================
;; Custom Slot: Filtered Core Items
;; ============================================================================

(gen-class
  :name my_mod.forge1165.gui.SlotFilteredCore
  :extends net.minecraft.inventory.container.Slot
  :constructors {[net.minecraft.inventory.IInventory int int int] 
                 [net.minecraft.inventory.IInventory int int int]}
  :state state
  :init init-core)

(defn -init-core
  "Initialize filtered core slot"
  [inventory slot-index x y]
  [[inventory slot-index x y] {}])

(defn -isItemValid
  "Only allow core-type items in this slot"
  [this stack]
  (core/is-mat-core? stack))

(defn -getMaxStackSize
  "Core items never stack"
  [this]
  1)

;; ============================================================================
;; Custom Slot: Output Only (No Insertion)
;; ============================================================================

(gen-class
  :name my_mod.forge1165.gui.SlotOutput
  :extends net.minecraft.inventory.container.Slot
  :constructors {[net.minecraft.inventory.IInventory int int int] 
                 [net.minecraft.inventory.IInventory int int int]}
  :state state
  :init init-output)

(defn -init-output
  "Initialize output-only slot"
  [inventory slot-index x y]
  [[inventory slot-index x y] {}])

(defn -isItemValid
  "Never allow direct insertion into output slot"
  [this stack]
  false)

(defn -canTakeStack
  "Allow taking items from output slot"
  [this player]
  true)

;; ============================================================================
;; Slot Factory Functions
;; ============================================================================

(defn create-energy-slot
  "Create a slot that only accepts energy items
  
  Args:
  - inventory: IInventory
  - slot-index: int (slot index in inventory)
  - x: int (GUI x position)
  - y: int (GUI y position)
  
  Returns: Slot instance"
  [inventory slot-index x y]
  (my_mod.forge1165.gui.SlotEnergyItem. inventory slot-index x y))

(defn create-plate-slot
  "Create a slot that filters for plate-type items"
  [inventory slot-index x y]
  (my_mod.forge1165.gui.SlotFilteredPlate. inventory slot-index x y))

(defn create-core-slot
  "Create a slot that filters for core-type items"
  [inventory slot-index x y]
  (my_mod.forge1165.gui.SlotFilteredCore. inventory slot-index x y))

(defn create-output-slot
  "Create an output-only slot (no insertion allowed)"
  [inventory slot-index x y]
  (my_mod.forge1165.gui.SlotOutput. inventory slot-index x y))

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
  - container: Container to add slots to
  - player-inventory: PlayerInventory
  - x-offset: int (left edge x position)
  - y-offset: int (top edge y position for main inventory)
  
  Side effects: Adds 36 slots to container"
  [container player-inventory x-offset y-offset]
  
  ;; Main inventory (3 rows × 9 columns)
  (doseq [row (range 3)
          col (range 9)]
    (let [slot-index (+ (* row 9) col 9)  ; Skip hotbar (0-8)
          x (+ x-offset (* col 18))
          y (+ y-offset (* row 18))]
      (.addSlot container (create-standard-slot player-inventory slot-index x y))))
  
  ;; Hotbar (1 row × 9 columns)
  (doseq [col (range 9)]
    (let [slot-index col
          x (+ x-offset (* col 18))
          y (+ y-offset 58)]  ; 58 = 3 rows * 18 + 4px gap
      (.addSlot container (create-standard-slot player-inventory slot-index x y)))))

(defn add-gui-slots
  "Add GUI-specific slots based on metadata-driven layout
  
  Platform-agnostic implementation: Reads slot layout from gui-metadata
  and creates appropriate slot types dynamically.
  
  Args:
  - container: Container to add slots to
  - inventory: IInventory for the GUI
  - gui-id: int (GUI identifier)
  - x-offset: int (base x position)
  - y-offset: int (base y position)
  
  Side effects: Adds slots to container based on layout"
  [container inventory gui-id x-offset y-offset]
  (when-let [layout (gui-metadata/get-slot-layout gui-id)]
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
  (gui-metadata/get-slot-range gui-id section))

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
                   (-> stack .getItem .getRegistryName .toString))))))

(defn validate-slot-setup
  "Validate that container slots are set up correctly
  
  Args:
  - container: Container
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

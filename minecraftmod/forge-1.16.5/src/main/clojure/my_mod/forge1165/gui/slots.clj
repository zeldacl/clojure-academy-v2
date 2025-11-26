(ns my-mod.forge1165.gui.slots
  "Forge 1.16.5 GUI Slot Implementations"
  (:require [my-mod.energy.stub :as energy]
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
;; Custom Slot: Constraint Plate Only
;; ============================================================================

(gen-class
  :name my_mod.forge1165.gui.SlotConstraintPlate
  :extends net.minecraft.inventory.container.Slot
  :constructors {[net.minecraft.inventory.IInventory int int int] 
                 [net.minecraft.inventory.IInventory int int int]}
  :state state
  :init init-plate)

(defn -init-plate
  "Initialize constraint plate slot"
  [inventory slot-index x y]
  [[inventory slot-index x y] {}])

(defn -isItemValid
  "Only allow constraint plates in this slot"
  [this stack]
  (plate/is-constraint-plate? stack))

(defn -getMaxStackSize
  "Plates can stack but matrix only needs one per slot"
  [this]
  1)

;; ============================================================================
;; Custom Slot: Matrix Core Only
;; ============================================================================

(gen-class
  :name my_mod.forge1165.gui.SlotMatrixCore
  :extends net.minecraft.inventory.container.Slot
  :constructors {[net.minecraft.inventory.IInventory int int int] 
                 [net.minecraft.inventory.IInventory int int int]}
  :state state
  :init init-core)

(defn -init-core
  "Initialize matrix core slot"
  [inventory slot-index x y]
  [[inventory slot-index x y] {}])

(defn -isItemValid
  "Only allow matrix cores in this slot"
  [this stack]
  (core/is-mat-core? stack))

(defn -getMaxStackSize
  "Cores never stack"
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
  "Create a slot that only accepts constraint plates"
  [inventory slot-index x y]
  (my_mod.forge1165.gui.SlotConstraintPlate. inventory slot-index x y))

(defn create-core-slot
  "Create a slot that only accepts matrix cores"
  [inventory slot-index x y]
  (my_mod.forge1165.gui.SlotMatrixCore. inventory slot-index x y))

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

(defn add-node-slots
  "Add wireless node slots (input + output)
  
  Args:
  - container: Container
  - node-inventory: IInventory
  - x-offset: int
  - y-offset: int"
  [container node-inventory x-offset y-offset]
  
  ;; Input slot (left)
  (.addSlot container 
    (create-energy-slot node-inventory 0 x-offset y-offset))
  
  ;; Output slot (right)
  (.addSlot container 
    (create-energy-slot node-inventory 1 (+ x-offset 26) y-offset)))

(defn add-matrix-slots
  "Add wireless matrix slots (3 plates + 1 core)
  
  Args:
  - container: Container
  - matrix-inventory: IInventory
  - x-offset: int
  - y-offset: int"
  [container matrix-inventory x-offset y-offset]
  
  ;; Plate slots (horizontal row)
  (doseq [i (range 3)]
    (.addSlot container 
      (create-plate-slot matrix-inventory i 
                        (+ x-offset (* i 34)) 
                        y-offset)))
  
  ;; Core slot (centered below plates)
  (.addSlot container 
    (create-core-slot matrix-inventory 3 
                     (+ x-offset 47)  ; Center of 3 slots
                     (+ y-offset 24))))

;; ============================================================================
;; Slot Index Helpers
;; ============================================================================

(defn get-slot-range
  "Get slot index range for different inventory sections
  
  Args:
  - container-type: :node or :matrix
  - section: :tile, :player-main, :player-hotbar
  
  Returns: [start-index end-index] (inclusive)"
  [container-type section]
  (case container-type
    :node
    (case section
      :tile [0 1]
      :player-main [2 28]
      :player-hotbar [29 37]
      [0 0])
    
    :matrix
    (case section
      :tile [0 3]
      :player-main [4 30]
      :player-hotbar [31 39]
      [0 0])))

(defn slot-in-range?
  "Check if slot index is in given section
  
  Args:
  - slot-index: int
  - container-type: :node or :matrix
  - section: :tile, :player-main, :player-hotbar
  
  Returns: boolean"
  [slot-index container-type section]
  (let [[start end] (get-slot-range container-type section)]
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

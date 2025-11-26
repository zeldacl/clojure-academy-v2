(ns my-mod.fabric1201.gui.slots
  "Fabric 1.20.1 GUI Slot Implementations
  
  This is adapted from Forge's slot system with Fabric API compatibility"
  (:require [my-mod.energy.stub :as energy]
            [my-mod.item.constraint-plate :as plate]
            [my-mod.item.mat-core :as core]
            [my-mod.util.log :as log]
            [my-mod.wireless.gui.gui-metadata :as gui-metadata])
  (:import [net.minecraft.screen.slot Slot]
           [net.minecraft.inventory Inventory]
           [net.minecraft.item ItemStack]
           [net.minecraft.entity.player PlayerEntity]))

;; ============================================================================
;; Custom Slot: Energy Item Only
;; ============================================================================

(gen-class
  :name my_mod.fabric1201.gui.SlotEnergyItem
  :extends net.minecraft.screen.slot.Slot
  :constructors {[net.minecraft.inventory.Inventory int int int] 
                 [net.minecraft.inventory.Inventory int int int]}
  :state state
  :init init)

(defn -init
  "Initialize energy item slot"
  [inventory slot-index x y]
  [[inventory slot-index x y] {}])

(defn -canInsert
  "Only allow energy items in this slot (Fabric's isItemValid)"
  [this stack]
  (energy/is-energy-item-supported? stack))

(defn -getMaxItemCount
  "Energy items usually don't stack, but allow it if they do"
  [this]
  1)

;; ============================================================================
;; Custom Slot: Constraint Plate Only
;; ============================================================================

(gen-class
  :name my_mod.fabric1201.gui.SlotConstraintPlate
  :extends net.minecraft.screen.slot.Slot
  :constructors {[net.minecraft.inventory.Inventory int int int] 
                 [net.minecraft.inventory.Inventory int int int]}
  :state state
  :init init-plate)

(defn -init-plate
  "Initialize constraint plate slot"
  [inventory slot-index x y]
  [[inventory slot-index x y] {}])

(defn -canInsert
  "Only allow constraint plates in this slot"
  [this stack]
  (plate/is-constraint-plate? stack))

(defn -getMaxItemCount
  "Plates can stack but matrix only needs one per slot"
  [this]
  1)

;; ============================================================================
;; Custom Slot: Matrix Core Only
;; ============================================================================

(gen-class
  :name my_mod.fabric1201.gui.SlotMatrixCore
  :extends net.minecraft.screen.slot.Slot
  :constructors {[net.minecraft.inventory.Inventory int int int] 
                 [net.minecraft.inventory.Inventory int int int]}
  :state state
  :init init-core)

(defn -init-core
  "Initialize matrix core slot"
  [inventory slot-index x y]
  [[inventory slot-index x y] {}])

(defn -canInsert
  "Only allow matrix cores in this slot"
  [this stack]
  (core/is-mat-core? stack))

(defn -getMaxItemCount
  "Cores never stack"
  [this]
  1)

;; ============================================================================
;; Custom Slot: Output Only (No Insertion)
;; ============================================================================

(gen-class
  :name my_mod.fabric1201.gui.SlotOutput
  :extends net.minecraft.screen.slot.Slot
  :constructors {[net.minecraft.inventory.Inventory int int int] 
                 [net.minecraft.inventory.Inventory int int int]}
  :state state
  :init init-output)

(defn -init-output
  "Initialize output-only slot"
  [inventory slot-index x y]
  [[inventory slot-index x y] {}])

(defn -canInsert
  "Never allow direct insertion into output slot"
  [this stack]
  false)

(defn -canTakeItems
  "Allow taking items from output slot (Fabric's canTakeStack)"
  [this player]
  true)

;; ============================================================================
;; Slot Factory Functions
;; ============================================================================

(defn create-energy-slot
  "Create a slot that only accepts energy items
  
  Args:
  - inventory: Inventory
  - slot-index: int (slot index in inventory)
  - x: int (GUI x position)
  - y: int (GUI y position)
  
  Returns: Slot instance"
  [inventory slot-index x y]
  (my_mod.fabric1201.gui.SlotEnergyItem. inventory slot-index x y))

(defn create-plate-slot
  "Create a slot that only accepts constraint plates"
  [inventory slot-index x y]
  (my_mod.fabric1201.gui.SlotConstraintPlate. inventory slot-index x y))

(defn create-core-slot
  "Create a slot that only accepts matrix cores"
  [inventory slot-index x y]
  (my_mod.fabric1201.gui.SlotMatrixCore. inventory slot-index x y))

(defn create-output-slot
  "Create an output-only slot (no insertion allowed)"
  [inventory slot-index x y]
  (my_mod.fabric1201.gui.SlotOutput. inventory slot-index x y))

(defn create-standard-slot
  "Create a standard slot (accepts any item)"
  [inventory slot-index x y]
  (Slot. inventory slot-index x y))

;; ============================================================================
;; Slot Layout Helpers (Metadata-Driven)
;; ============================================================================

(defn create-slot-by-type
  "Create a slot based on metadata type definition
  
  Args:
  - handler: ScreenHandler to add slot to
  - inventory: Inventory
  - slot-def: {:type :energy/:plate/:core, :index int, :x int, :y int}
  - x-offset: int (additional x offset)
  - y-offset: int (additional y offset)
  
  Side effects: Adds slot to handler"
  [handler inventory slot-def x-offset y-offset]
  (let [{:keys [type index x y]} slot-def]
    (.addSlot handler
      (case type
        :energy (create-energy-slot inventory index (+ x x-offset) (+ y y-offset))
        :plate (create-plate-slot inventory index (+ x x-offset) (+ y y-offset))
        :core (create-core-slot inventory index (+ x x-offset) (+ y y-offset))
        (throw (ex-info "Unknown slot type" {:type type}))))))

(defn add-gui-slots
  "Generic function to add GUI-specific slots based on metadata
  
  Replaces add-node-slots and add-matrix-slots with metadata-driven approach.
  
  Args:
  - handler: ScreenHandler
  - inventory: Inventory
  - gui-id: int (0 for node, 1 for matrix, etc.)
  - x-offset: int
  - y-offset: int
  
  Side effects: Adds all GUI slots to handler based on layout in gui_metadata.clj"
  [handler inventory gui-id x-offset y-offset]
  (let [layout (gui-metadata/get-slot-layout gui-id)]
    (when layout
      (doseq [slot-def (:slots layout)]
        (create-slot-by-type handler inventory slot-def x-offset y-offset)))))

(defn get-gui-slot-ranges
  "Get slot index ranges for GUI type from metadata
  
  Replaces get-slot-range with metadata-driven approach.
  
  Args:
  - gui-id: int (0 for node, 1 for matrix, etc.)
  - section: :tile, :player-main, :player-hotbar
  
  Returns: [start-index end-index] (inclusive)"
  [gui-id section]
  (get-in (gui-metadata/get-slot-ranges gui-id) [section] [0 0]))

;; ============================================================================
;; Slot Layout Helpers (Legacy - Will Be Deprecated)
;; ============================================================================

(defn add-player-inventory-slots
  "Add standard player inventory slots (3x9 grid + hotbar)
  
  Args:
  - handler: ScreenHandler to add slots to
  - player-inventory: PlayerInventory
  - x-offset: int (left edge x position)
  - y-offset: int (top edge y position for main inventory)
  
  Side effects: Adds 36 slots to handler"
  [handler player-inventory x-offset y-offset]
  
  ;; Main inventory (3 rows × 9 columns)
  (doseq [row (range 3)
          col (range 9)]
    (let [slot-index (+ (* row 9) col 9)  ; Skip hotbar (0-8)
          x (+ x-offset (* col 18))
          y (+ y-offset (* row 18))]
      (.addSlot handler (create-standard-slot player-inventory slot-index x y))))
  
  ;; Hotbar (1 row × 9 columns)
  (doseq [col (range 9)]
    (let [slot-index col
          x (+ x-offset (* col 18))
          y (+ y-offset 58)]  ; 58 = 3 rows * 18 + 4px gap
      (.addSlot handler (create-standard-slot player-inventory slot-index x y)))))

;; ============================================================================
;; Slot Index Helpers
;; ============================================================================

(defn get-slot-range
  "Get slot index range for different inventory sections
  
  Args:
  - handler-type: :node or :matrix
  - section: :tile, :player-main, :player-hotbar
  
  Returns: [start-index end-index] (inclusive)"
  [handler-type section]
  (case handler-type
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
  - handler-type: :node or :matrix
  - section: :tile, :player-main, :player-hotbar
  
  Returns: boolean"
  [slot-index handler-type section]
  (let [[start end] (get-slot-range handler-type section)]
    (<= start slot-index end)))

;; ============================================================================
;; Debugging Helpers
;; ============================================================================

(defn log-slot-contents
  "Log all slot contents for debugging"
  [handler]
  (log/debug "ScreenHandler slots:")
  (doseq [i (range (.slots handler))]
    (let [slot (.getSlot handler i)
          stack (.getStack slot)]
      (when-not (.isEmpty stack)
        (log/debug "  Slot" i ":" 
                   (.getCount stack) "x" 
                   (-> stack .getItem .toString))))))

(defn validate-slot-setup
  "Validate that handler slots are set up correctly
  
  Args:
  - handler: ScreenHandler
  - expected-count: int
  
  Returns: boolean"
  [handler expected-count]
  (let [actual-count (count (.slots handler))]
    (if (= actual-count expected-count)
      (do
        (log/info "Slot validation passed:" actual-count "slots")
        true)
      (do
        (log/error "Slot validation failed: expected" expected-count 
                   "but got" actual-count)
        false))))

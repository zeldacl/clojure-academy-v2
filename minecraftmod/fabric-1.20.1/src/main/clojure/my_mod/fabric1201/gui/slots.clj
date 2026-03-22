(ns cn.li.fabric1201.gui.slots
  "Fabric 1.20.1 GUI Slot Implementations
  
  This is adapted from Forge's slot system with Fabric API compatibility"
  (:require [cn.li.ac.gui.platform-adapter :as gui]
            [cn.li.ac.gui.slot-validators :as slot-validators]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.screen.slot Slot]
           [net.minecraft.screen ScreenHandler]
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
  (slot-validators/energy-item-validator stack))

(defn -getMaxItemCount
  "Energy items usually don't stack, but allow it if they do"
  [this]
  1)

;; ============================================================================
;; Custom Slot: Filtered Plate Items
;; ============================================================================

(gen-class
  :name my_mod.fabric1201.gui.SlotFilteredPlate
  :extends net.minecraft.screen.slot.Slot
  :constructors {[net.minecraft.inventory.Inventory int int int] 
                 [net.minecraft.inventory.Inventory int int int]}
  :state state
  :init init-plate)

(defn -init-plate
  "Initialize filtered plate slot"
  [inventory slot-index x y]
  [[inventory slot-index x y] {}])

(defn -canInsert
  "Only allow plate-type items in this slot"
  [this stack]
  (slot-validators/constraint-plate-validator stack))

(defn -getMaxItemCount
  "Plate items limited to single stack"
  [this]
  1)

;; ============================================================================
;; Custom Slot: Filtered Core Items
;; ============================================================================

(gen-class
  :name my_mod.fabric1201.gui.SlotFilteredCore
  :extends net.minecraft.screen.slot.Slot
  :constructors {[net.minecraft.inventory.Inventory int int int] 
                 [net.minecraft.inventory.Inventory int int int]}
  :state state
  :init init-core)

(defn -init-core
  "Initialize filtered core slot"
  [inventory slot-index x y]
  [[inventory slot-index x y] {}])

(defn -canInsert
  "Only allow core-type items in this slot"
  [this stack]
  (slot-validators/matrix-core-validator stack))

(defn -getMaxItemCount
  "Core items never stack"
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
  (slot-validators/output-slot-validator stack))

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
  "Create a slot that filters for plate-type items"
  [inventory slot-index x y]
  (my_mod.fabric1201.gui.SlotFilteredPlate. inventory slot-index x y))

(defn create-core-slot
  "Create a slot that filters for core-type items"
  [inventory slot-index x y]
  (my_mod.fabric1201.gui.SlotFilteredCore. inventory slot-index x y))

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
  [^ScreenHandler handler inventory slot-def x-offset y-offset]
  (let [{:keys [type index x y]} slot-def
        ^Slot s (case type
                  :energy (create-energy-slot inventory index (+ x x-offset) (+ y y-offset))
                  :plate (create-plate-slot inventory index (+ x x-offset) (+ y y-offset))
                  :core (create-core-slot inventory index (+ x x-offset) (+ y y-offset))
                  (throw (ex-info "Unknown slot type" {:type type})))]
    (.addSlot handler s)))

(defn add-gui-slots
  "Generic function to add GUI-specific slots based on metadata
  
  Platform-agnostic implementation: Uses metadata-driven approach.
  
  Args:
  - handler: ScreenHandler
  - inventory: Inventory
  - gui-id: int (GUI identifier from gui-metadata)
  - x-offset: int
  - y-offset: int
  
  Side effects: Adds all GUI slots to handler based on layout in gui_metadata.clj"
  [^ScreenHandler handler inventory gui-id x-offset y-offset]
  (let [layout (gui/get-slot-layout gui-id)]
    (when layout
      (doseq [slot-def (:slots layout)]
        (create-slot-by-type handler inventory slot-def x-offset y-offset)))))

(defn get-gui-slot-ranges
  "Get slot index ranges for GUI type from metadata
  
  Platform-agnostic implementation: Uses metadata-driven approach.
  
  Args:
  - gui-id: int (GUI identifier from gui-metadata)
  - section: :tile, :player-main, :player-hotbar
  
  Returns: [start-index end-index] (inclusive)"
  [gui-id section]
  (gui/get-slot-range gui-id section))

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
  [^ScreenHandler handler player-inventory x-offset y-offset]
  
  ;; Main inventory (3 rows × 9 columns)
    (doseq [row (range 3)
        col (range 9)]
      (let [slot-index (+ (* row 9) col 9)  ; Skip hotbar (0-8)
        x (+ x-offset (* col 18))
        y (+ y-offset (* row 18))
        ^Slot s (create-standard-slot player-inventory slot-index x y)]
    (.addSlot handler s)))
  
  ;; Hotbar (1 row × 9 columns)
  (doseq [col (range 9)]
    (let [slot-index col
          x (+ x-offset (* col 18))
          y (+ y-offset 58)  ; 58 = 3 rows * 18 + 4px gap
          ^Slot s (create-standard-slot player-inventory slot-index x y)]
      (.addSlot handler s))))

;; ============================================================================
;; Slot Index Helpers
;; ============================================================================

(defn get-slot-range
  "DEPRECATED: Use get-gui-slot-ranges instead
  
  Get slot index range for different inventory sections
  
  Args:
  - gui-id: int (GUI identifier)
  - section: :tile, :player-main, :player-hotbar
  
  Returns: [start-index end-index] (inclusive)"
  [gui-id section]
  (get-gui-slot-ranges gui-id section))

(defn slot-in-range?
  "Check if slot index is in given section
  
  Args:
  - slot-index: int
  - gui-id: int (GUI identifier)
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
  (let [^java.util.List slots (.slots handler)]
    (doseq [i (range (.size slots))]
      (let [^Slot slot (.get slots i)
            ^ItemStack stack (.getStack slot)]
        (when-not (.isEmpty stack)
          (log/debug "  Slot" i ":" 
                     (.getCount stack) "x" 
                     (-> stack .getItem .toString)))))))

(defn validate-slot-setup
  "Validate that handler slots are set up correctly
  
  Args:
  - handler: ScreenHandler
  - expected-count: int
  
  Returns: boolean"
  [^ScreenHandler handler expected-count]
  (let [^java.util.List slots (.slots handler)
        actual-count (.size slots)]
    (if (= actual-count expected-count)
      (do
        (log/info "Slot validation passed:" actual-count "slots")
        true)
      (do
        (log/error "Slot validation failed: expected" expected-count 
                   "but got" actual-count)
        false))))

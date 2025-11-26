(ns my-mod.wireless.gui.slot-manager
  "Platform-agnostic slot layout and quick-move logic for GUI containers
  
  This namespace provides slot layout information and quick-move strategies
  that are independent of platform-specific Container/ScreenHandler implementations.
  
  Platform-specific bridge code should delegate to these functions instead of
  implementing slot logic directly."
  (:require [my-mod.wireless.gui.node-container :as node-container]
            [my-mod.wireless.gui.matrix-container :as matrix-container]
            [my-mod.util.log :as log]))

;; ============================================================================
;; Slot Layout Constants
;; ============================================================================

(def node-tile-slots
  "Node container tile slots range: [0, 2)"
  {:start 0 :end 2 :count 2})

(def node-player-slots
  "Node container player slots range: [2, 38)
  36 slots total: 27 main inventory + 9 hotbar"
  {:start 2 :end 38 :count 36})

(def matrix-tile-slots
  "Matrix container tile slots range: [0, 4)"
  {:start 0 :end 4 :count 4})

(def matrix-player-slots
  "Matrix container player slots range: [4, 40)
  36 slots total: 27 main inventory + 9 hotbar"
  {:start 4 :end 40 :count 36})

;; ============================================================================
;; Slot Layout Queries
;; ============================================================================

(defn get-tile-slot-range
  "Get tile entity slot range for container
  
  Args:
  - container: NodeContainer or MatrixContainer
  
  Returns: {:start int :end int :count int}"
  [container]
  (cond
    (instance? my_mod.wireless.gui.node_container.NodeContainer container)
    node-tile-slots
    
    (instance? my_mod.wireless.gui.matrix_container.MatrixContainer container)
    matrix-tile-slots
    
    :else
    (do
      (log/warn "Unknown container type for tile slots:" (type container))
      {:start 0 :end 0 :count 0})))

(defn get-player-slot-range
  "Get player inventory slot range for container
  
  Args:
  - container: NodeContainer or MatrixContainer
  
  Returns: {:start int :end int :count int}"
  [container]
  (cond
    (instance? my_mod.wireless.gui.node_container.NodeContainer container)
    node-player-slots
    
    (instance? my_mod.wireless.gui.matrix_container.MatrixContainer container)
    matrix-player-slots
    
    :else
    (do
      (log/warn "Unknown container type for player slots:" (type container))
      {:start 0 :end 0 :count 0})))

(defn is-tile-slot?
  "Check if slot index is a tile entity slot
  
  Args:
  - container: NodeContainer or MatrixContainer
  - slot-index: int
  
  Returns: boolean"
  [container slot-index]
  (let [{:keys [start end]} (get-tile-slot-range container)]
    (and (>= slot-index start) (< slot-index end))))

(defn is-player-slot?
  "Check if slot index is a player inventory slot
  
  Args:
  - container: NodeContainer or MatrixContainer
  - slot-index: int
  
  Returns: boolean"
  [container slot-index]
  (let [{:keys [start end]} (get-player-slot-range container)]
    (and (>= slot-index start) (< slot-index end))))

;; ============================================================================
;; Quick Move Strategy
;; ============================================================================

(defn get-quick-move-strategy
  "Get quick-move target range for a slot
  
  Logic:
  - Tile slot -> Move to player inventory
  - Player slot -> Move to tile slots
  
  Args:
  - container: NodeContainer or MatrixContainer
  - slot-index: int
  
  Returns: {:target-start int :target-end int :reverse boolean}
           or nil if no move possible"
  [container slot-index]
  (cond
    ;; From tile to player
    (is-tile-slot? container slot-index)
    (let [{:keys [start end]} (get-player-slot-range container)]
      {:target-start start
       :target-end end
       :reverse true  ; Insert from end (hotbar first)
       :source :tile})
    
    ;; From player to tile
    (is-player-slot? container slot-index)
    (let [{:keys [start end]} (get-tile-slot-range container)]
      {:target-start start
       :target-end end
       :reverse false  ; Insert from start
       :source :player})
    
    :else
    (do
      (log/warn "Unknown slot index for quick-move:" slot-index)
      nil)))

;; ============================================================================
;; Platform Bridge Helpers
;; ============================================================================

(defn execute-quick-move-forge
  "Execute quick-move using Forge's Container API
  
  This is a helper for Forge bridge code to implement quickMoveStack.
  
  Args:
  - container-wrapper: Forge Container (with .moveItemStackTo method)
  - clj-container: Clojure NodeContainer or MatrixContainer
  - slot-index: int
  - slot: Forge Slot object
  - stack: ItemStack
  
  Returns: ItemStack (EMPTY if moved, original if failed)"
  [container-wrapper clj-container slot-index slot stack]
  (if-let [strategy (get-quick-move-strategy clj-container slot-index)]
    (let [{:keys [target-start target-end reverse]} strategy]
      (if (.moveItemStackTo container-wrapper stack target-start target-end reverse)
        (do
          (.setChanged slot)
          net.minecraft.item.ItemStack/EMPTY)
        stack))
    stack))

(defn execute-quick-move-fabric
  "Execute quick-move using Fabric's ScreenHandler API
  
  This is a helper for Fabric bridge code to implement quickMove (transferSlot).
  
  Args:
  - handler-wrapper: Fabric ScreenHandler (with .insertItem method)
  - clj-container: Clojure NodeContainer or MatrixContainer
  - slot-index: int
  - slot: Fabric Slot object
  - stack: ItemStack
  
  Returns: ItemStack (EMPTY if moved, original if failed)"
  [handler-wrapper clj-container slot-index slot stack]
  (if-let [strategy (get-quick-move-strategy clj-container slot-index)]
    (let [{:keys [target-start target-end reverse]} strategy]
      (if (.insertItem handler-wrapper stack target-start target-end reverse)
        (do
          (.markDirty slot)
          net.minecraft.item.ItemStack/EMPTY)
        stack))
    stack))

;; ============================================================================
;; Design Notes
;; ============================================================================

;; This slot manager provides:
;;
;; 1. **Slot Layout Information**:
;;    - Tile slot ranges (node: 0-1, matrix: 0-3)
;;    - Player slot ranges (always 36 slots, but offset varies)
;;    - Queries: is-tile-slot?, is-player-slot?
;;
;; 2. **Quick-Move Strategy**:
;;    - Determines target range for Shift+Click
;;    - Handles reverse insertion (hotbar-first for tile->player)
;;    - Platform-agnostic logic
;;
;; 3. **Platform Bridge Helpers**:
;;    - execute-quick-move-forge: Uses .moveItemStackTo
;;    - execute-quick-move-fabric: Uses .insertItem
;;    - Both delegate to same strategy logic
;;
;; Benefits:
;; - DRY: Slot layout defined once
;; - Platform-agnostic: Core logic independent of Forge/Fabric
;; - Maintainable: Changes to slot layout in one place
;; - Testable: Pure functions without platform dependencies

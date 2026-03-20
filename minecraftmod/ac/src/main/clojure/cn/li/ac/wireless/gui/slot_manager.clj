(ns cn.li.ac.wireless.gui.slot-manager
  "Platform-agnostic slot layout and quick-move logic for GUI containers
  
  This namespace provides slot layout information and quick-move strategies
  that are independent of platform-specific Container/ScreenHandler implementations.
  
  Platform-specific bridge code should delegate to these functions instead of
  implementing slot logic directly."
  (:require [cn.li.mcmod.util.log :as log]))

  (defn- node-container?
    [container]
    (= (:container-type container) :node))

  (defn- matrix-container?
    [container]
    (= (:container-type container) :matrix))

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
    (node-container? container)
    node-tile-slots
    
    (matrix-container? container)
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
    (node-container? container)
    node-player-slots
    
    (matrix-container? container)
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

;; Platform-specific quick-move implementations should be in:
;; - forge-1.*.*/src/main/clojure/.../slot_manager_impl.clj
;; - fabric-1.*.*/src/main/clojure/.../slot_manager_impl.clj
;;
;; These implementations should delegate to get-quick-move-strategy
;; and use platform-specific APIs (Forge .moveItemStackTo or Fabric .insertItem)

;; ============================================================================
;; Design Notes
;; ============================================================================

;; This slot manager provides platform-agnostic slot management:
;;
;; 1. **Slot Layout Information** (Platform-Independent):
;;    - Tile slot ranges (node: 0-1, matrix: 0-3)
;;    - Player slot ranges (always 36 slots, but offset varies)
;;    - Queries: is-tile-slot?, is-player-slot?
;;
;; 2. **Quick-Move Strategy** (Platform-Independent):
;;    - Determines target range for Shift+Click
;;    - Handles reverse insertion (hotbar-first for tile->player)
;;    - No platform-specific APIs used
;;
;; 3. **Platform Bridge Hook Points**:
;;    - Each platform implements execute-quick-move-* in their own module
;;    - Uses get-quick-move-strategy to access business logic
;;    - Calls platform-specific APIs (.moveItemStackTo for Forge, .insertItem for Fabric)
;;
;; Benefits:
;; - DRY: Slot layout and strategy defined once
;; - Platform-agnostic core: Independent of Forge/Fabric APIs
;; - Modular: Platform code in platform-specific modules
;; - Testable: Pure functions without platform dependencies

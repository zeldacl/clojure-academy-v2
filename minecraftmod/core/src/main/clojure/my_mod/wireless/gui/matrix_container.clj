(ns my-mod.wireless.gui.matrix-container
  "Wireless Matrix GUI Container - handles 4 slots and multiblock data sync"
  (:require [my-mod.util.log :as log]
            [my-mod.block.wireless-matrix :as wm]
            [my-mod.item.constraint-plate :as plate]
            [my-mod.item.mat-core :as core]))

;; ============================================================================
;; Container Data Structure
;; ============================================================================

(defrecord MatrixContainer
  [tile-entity        ; Matrix master TileEntity reference
   tile-java          ; MatrixJavaProxy - Java-compatible accessor wrapper
   player             ; Player who opened GUI
   
   ;; Synced data (updated from server -> client)
   core-level         ; atom<int> - Core tier (0-4)
   plate-count        ; atom<int> - Number of plates (0-3)
   is-working         ; atom<boolean> - Multiblock formed?
   capacity           ; atom<int> - Current capacity
   max-capacity       ; atom<int> - Maximum capacity
   bandwidth          ; atom<int> - IF/t transfer rate
   range])            ; atom<double> - Network range

;; ============================================================================
;; Container Creation
;; ============================================================================

(defn create-container
  "Create a Matrix GUI container instance
  
  Args:
  - tile: Matrix TileEntity instance
  - player: Player who opened GUI
  
  Returns: MatrixContainer record"
  [tile player]
  (->MatrixContainer
    tile
    (wm/MatrixJavaProxy. tile)  ; Wrap tile in Java accessor proxy
    player
    ;; Initialize synced data
    (atom 0)    ; core-level
    (atom 0)    ; plate-count
    (atom false) ; is-working
    (atom 0)    ; capacity
    (atom 0)    ; max-capacity
    (atom 0)    ; bandwidth
    (atom 0.0))) ; range

;; ============================================================================
;; Slot Management
;; ============================================================================

(def slot-plate-1 0)
(def slot-plate-2 1)
(def slot-plate-3 2)
(def slot-core 3)

(defn get-slot-count
  "Get total slot count (4 for matrix: 3 plates + 1 core)"
  [_container]
  4)

(defn is-plate-slot?
  "Check if slot is a plate slot"
  [slot-index]
  (<= 0 slot-index 2))

(defn is-core-slot?
  "Check if slot is the core slot"
  [slot-index]
  (= slot-index slot-core))

(defn can-place-item?
  "Check if item can be placed in slot
  
  Slots 0-2 (plates): Only wireless constraint plates
  Slot 3 (core): Only wireless matrix cores"
  [container slot-index item-stack]
  (cond
    (is-plate-slot? slot-index)
    ;; Check if item is a constraint plate
    (plate/is-constraint-plate? item-stack)
    
    (is-core-slot? slot-index)
    ;; Check if item is a wireless matrix core
    (core/is-mat-core? item-stack)
    
    :else false))

(defn get-slot-item
  "Get item from slot"
  [container slot-index]
  (let [inventory @(:inventory (:tile-entity container))]
    (get inventory slot-index)))

(defn set-slot-item!
  "Set item in slot"
  [container slot-index item-stack]
  (let [tile (:tile-entity container)
        inventory-atom (:inventory tile)]
    (swap! inventory-atom assoc slot-index item-stack)))

(defn slot-changed!
  "Called when slot contents change - triggers multiblock revalidation"
  [container slot-index]
  (log/info "Matrix container slot" slot-index "changed")
  ;; Trigger multiblock structure validation
  (let [tile (:tile-entity container)]
    ;; In real implementation, call validate-structure!
    (log/info "Revalidating matrix structure...")))

;; ============================================================================
;; Data Synchronization
;; ============================================================================

(defn count-plates
  "Count number of plate items in slots"
  [container]
  (reduce (fn [count slot-idx]
            (if (get-slot-item container slot-idx)
              (inc count)
              count))
          0
          [slot-plate-1 slot-plate-2 slot-plate-3]))

(defn get-core-level
  "Get core tier level from core slot item
  
  Returns: int 0-4 (0 if no core)"
  [container]
  (let [core-item (get-slot-item container slot-core)]
    (if (and core-item (core/is-mat-core? core-item))
      ;; Get actual core level from item damage/NBT
      (core/get-core-level core-item)
      0)))

(defn calculate-matrix-stats
  "Calculate matrix stats based on core and plates
  
  Returns: Map with :capacity, :bandwidth, :range"
  [core-level plate-count]
  (let [;; Base stats from core
        base-capacity (* core-level 50000)
        base-bandwidth (* core-level 1000)
        base-range (* core-level 16.0)
        
        ;; Bonus from plates (each plate adds 20%)
        plate-multiplier (+ 1.0 (* plate-count 0.2))
        
        ;; Final stats
        capacity (int (* base-capacity plate-multiplier))
        bandwidth (int (* base-bandwidth plate-multiplier))
        range (* base-range plate-multiplier)]
    
    {:capacity capacity
     :bandwidth bandwidth
     :range range}))

(defn sync-to-client!
  "Update container data from tile entity (server -> client)
  
  Called every tick on server side"
  [container]
  (let [tile (:tile-entity container)
        
        ;; Count components
        plates (count-plates container)
        core-lvl (get-core-level container)
        
        ;; Check if multiblock is formed
        working? (and (> core-lvl 0)
                     (>= plates 0)) ; At least a core is required
        
        ;; Calculate stats
        stats (calculate-matrix-stats core-lvl plates)]
    
    ;; Update synced data
    (reset! (:core-level container) core-lvl)
    (reset! (:plate-count container) plates)
    (reset! (:is-working container) working?)
    
    (reset! (:capacity container) (:current-capacity tile 0))
    (reset! (:max-capacity container) (:capacity stats))
    (reset! (:bandwidth container) (:bandwidth stats))
    (reset! (:range container) (:range stats))))

(defn get-sync-data
  "Get data to sync to client
  
  Returns: Map of synced values"
  [container]
  {:core-level @(:core-level container)
   :plate-count @(:plate-count container)
   :is-working @(:is-working container)
   :capacity @(:capacity container)
   :max-capacity @(:max-capacity container)
   :bandwidth @(:bandwidth container)
   :range @(:range container)})

;; ============================================================================
;; Container Validation
;; ============================================================================

(defn still-valid?
  "Check if container is still valid for player
  
  Args:
  - container: MatrixContainer instance
  - player: Player instance
  
  Returns: boolean"
  [container player]
  (let [tile (:tile-entity container)
        world (:world tile)
        pos (:pos tile)
        max-distance 8.0]
    ;; Check if player is still close enough
    (and (= player (:player container))
         (< (.distanceSq (.getPos player) pos) (* max-distance max-distance)))))

;; ============================================================================
;; Container Update Tick
;; ============================================================================

(defn tick!
  "Called every tick on server side
  
  Updates synced data and handles multiblock logic"
  [container]
  ;; Sync data to client
  (sync-to-client! container)
  
  ;; Additional tick logic can go here
  ;; (e.g., particle effects, sound effects)
  )

;; ============================================================================
;; Button Actions
;; ============================================================================

(def button-toggle-working 0)
(def button-eject-core 1)
(def button-eject-plates 2)

(defn handle-button-click!
  "Handle button click from client
  
  Args:
  - container: MatrixContainer instance
  - button-id: int button ID
  - data: optional data map from client"
  [container button-id data]
  (let [tile (:tile-entity container)]
    (case button-id
      0 ; Toggle working state (enable/disable)
      (do
        ;; In real implementation, toggle matrix enabled state
        (log/info "Toggled matrix working state"))
      
      1 ; Eject core
      (do
        (set-slot-item! container slot-core nil)
        (log/info "Ejected matrix core"))
      
      2 ; Eject all plates
      (do
        (set-slot-item! container slot-plate-1 nil)
        (set-slot-item! container slot-plate-2 nil)
        (set-slot-item! container slot-plate-3 nil)
        (log/info "Ejected all plates"))
      
      (log/warn "Unknown button ID:" button-id))))

;; ============================================================================
;; Quick Move (Shift+Click)
;; ============================================================================

(defn quick-move-stack
  "Handle shift-click on slot
  
  Logic:
  - Plate slots -> Player inventory
  - Core slot -> Player inventory
  - Player inventory -> Appropriate slot (plates or core)
  
  Returns: ItemStack or nil"
  [container slot-index player-inventory-start]
  (cond
    ;; From matrix slots to player
    (< slot-index 4)
    (let [item (get-slot-item container slot-index)]
      (when item
        (set-slot-item! container slot-index nil)
        item))
    
    ;; From player inventory to matrix
    (>= slot-index player-inventory-start)
    (let [item (get-slot-item container slot-index)]
      (if item
        (cond
          ;; Try to place in core slot
          (can-place-item? container slot-core item)
          (if (nil? (get-slot-item container slot-core))
            (do
              (set-slot-item! container slot-core item)
              (set-slot-item! container slot-index nil)
              nil)  ; Return nil to indicate successful transfer
            item)  ; Return item if core slot occupied
          
          ;; Try to place in first empty plate slot
          (can-place-item? container slot-plate-1 item)
          (let [empty-plate-slot (first (filter #(nil? (get-slot-item container %))
                                                [slot-plate-1 slot-plate-2 slot-plate-3]))]
            (if empty-plate-slot
              (do
                (set-slot-item! container empty-plate-slot item)
                (set-slot-item! container slot-index nil)
                nil)  ; Return nil to indicate successful transfer
              item))  ; Return item if no empty plate slots
          
          ;; Can't place anywhere, return item unchanged
          :else item)
        nil))  ; No item in slot
    
    :else nil))

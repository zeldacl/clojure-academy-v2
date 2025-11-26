(ns my-mod.block.wireless-matrix
  "Wireless Matrix block implementation - 2x2x2 multiblock structure
  
  Core component of wireless energy network providing capacity, bandwidth and range."
  (:require [my-mod.block.dsl :as bdsl]
            [my-mod.wireless.interfaces :as winterfaces]
            [my-mod.item.constraint-plate :as plate]
            [my-mod.item.mat-core :as core]
            [my-mod.util.log :as log]))

;; ============================================================================
;; TileMatrix Record
;; ============================================================================

(defrecord TileMatrix
  [;; Identity
   placer-name        ; String - who placed this matrix
   
   ;; Inventory (4 slots)
   inventory          ; atom<vector> - [plate1 plate2 plate3 core]
   
   ;; Cached values (for client display)
   plate-count        ; atom<int> - number of plates installed (0-3)
   
   ;; Update counter
   update-ticker      ; atom<int> - tick counter for periodic updates
   
   ;; Multi-block info
   sub-id             ; int - 0 = origin, 1-7 = sub blocks
   direction          ; keyword - :north, :south, :east, :west
   
   ;; Position
   world              ; World object
   pos])              ; BlockPos

(defn create-matrix-tile-entity
  "Create a new matrix tile entity"
  [world pos]
  (map->TileMatrix
    {:placer-name ""
     :inventory (atom [nil nil nil nil])
     :plate-count (atom 0)
     :update-ticker (atom 0)
     :sub-id 0
     :direction :north
     :world world
     :pos pos}))

;; ============================================================================
;; Inventory Management
;; ============================================================================

(defn get-inventory-slot
  "Get item from inventory slot"
  [tile slot-index]
  (get @(:inventory tile) slot-index))

(defn set-inventory-slot!
  "Set item in inventory slot"
  [tile slot-index item-stack]
  (swap! (:inventory tile) assoc slot-index item-stack)
  ;; Update plate count if needed
  (when (<= 0 slot-index 2)
    (recalculate-plate-count! tile)))

(defn is-item-valid-for-slot?
  "Check if item can be placed in slot
  
  Slots 0-2: constraint_plate only
  Slot 3: mat_core only"
  [tile slot item-stack]
  (cond
    (nil? item-stack) true
    (<= 0 slot 2) (= (.getItem item-stack) plate/constraint-plate)
    (= slot 3) (core/is-mat-core? item-stack)
    :else false))

(defn get-inventory-stack-limit
  "Maximum stack size per slot"
  [tile]
  1)

(defn recalculate-plate-count!
  "Count non-empty plates in slots 0-2"
  [tile]
  (let [count (count (filter some? (take 3 @(:inventory tile))))]
    (reset! (:plate-count tile) count)
    count))

;; ============================================================================
;; Core Calculation Logic
;; ============================================================================

(defn get-plate-count
  "Get number of installed plates (0-3)"
  [tile]
  @(:plate-count tile))

(defn get-core-level
  "Get matrix core level (0-4)
  0 = no core, 1-4 = tier levels"
  [tile]
  (let [core-stack (get-inventory-slot tile 3)]
    (core/get-core-level core-stack)))

(defn is-working?
  "Check if matrix is operational
  Requires: 3 plates + core"
  [tile]
  (and (> (get-core-level tile) 0)
       (= (get-plate-count tile) 3)))

;; ============================================================================
;; IWirelessMatrix Protocol Implementation
;; ============================================================================

(extend-protocol winterfaces/IWirelessMatrix
  TileMatrix
  
  (get-matrix-capacity [this]
    (if (is-working? this)
      (* 8 (get-core-level this))
      0))
  
  (get-matrix-bandwidth [this]
    (if (is-working? this)
      (let [level (get-core-level this)]
        (* level level 60))
      0))
  
  (get-matrix-range [this]
    (if (is-working? this)
      (* 24 (Math/sqrt (get-core-level this)))
      0)))

;; Also implement IWirelessTile (marker via metadata)
(alter-meta! #'->TileMatrix assoc :wireless-tile true :wireless-matrix true)
(alter-meta! #'map->TileMatrix assoc :wireless-tile true :wireless-matrix true)

;; ============================================================================
;; Update Logic
;; ============================================================================

(defn update-matrix-tile!
  "Main update function - called every tick"
  [tile]
  (swap! (:update-ticker tile) inc)
  (let [tick @(:update-ticker tile)]
    ;; Sync to clients every 15 ticks
    (when (and (= (:sub-id tile) 0) ; Only origin block
               (zero? (mod tick 15)))
      (sync-to-clients! tile))
    
    ;; Verify structure integrity every 20 ticks (1 second)
    (when (zero? (mod tick 20))
      (verify-structure! tile))))

(defn sync-to-clients!
  "Synchronize matrix state to nearby clients"
  [tile]
  ;; TODO: Implement network sync
  (let [sync-data {:plate-count (get-plate-count tile)
                   :placer-name (:placer-name tile)
                   :working (is-working? tile)
                   :capacity (winterfaces/get-matrix-capacity tile)
                   :bandwidth (winterfaces/get-matrix-bandwidth tile)
                   :range (winterfaces/get-matrix-range tile)}]
    (log/debug "Sync matrix data:" sync-data)))

(defn verify-structure!
  "Verify multiblock structure integrity
  Destroys block if structure is broken"
  [tile]
  ;; TODO: Implement structure verification
  ;; For now, just log
  (log/debug "Verifying structure at" (:pos tile)))

;; ============================================================================
;; TileEntity Registry
;; ============================================================================

(defonce matrix-tiles (atom {}))

(defn register-matrix-tile! [pos tile]
  (swap! matrix-tiles assoc pos tile))

(defn unregister-matrix-tile! [pos]
  (swap! matrix-tiles dissoc pos))

(defn get-matrix-tile [pos]
  (get @matrix-tiles pos))

;; ============================================================================
;; ITickable Implementation
;; ============================================================================

(deftype TileMatrixTickable [^:volatile-mutable tile-data]
  Object
  (toString [this]
    (str "TileMatrixTickable@" (:pos tile-data)))
  
  clojure.lang.IDeref
  (deref [this] tile-data)
  
  clojure.lang.IFn
  (invoke [this]
    (when tile-data
      (try
        (update-matrix-tile! tile-data)
        (catch Exception e
          (log/error "Error updating matrix tile:" (.getMessage e))))))
  (invoke [this arg]
    (cond
      (= arg :get-tile-data) tile-data
      (= arg :update) (do (update-matrix-tile! tile-data) nil)
      :else nil)))

(defn create-tickable-matrix-tile-entity
  "Create a tickable matrix tile entity"
  [world pos]
  (let [tile-data (create-matrix-tile-entity world pos)]
    (TileMatrixTickable. tile-data)))

(defn get-tile-data
  "Extract TileMatrix data from tickable wrapper"
  [tickable-tile]
  (if (instance? TileMatrixTickable tickable-tile)
    @tickable-tile
    tickable-tile))

;; ============================================================================
;; Block Interaction Handlers
;; ============================================================================

(defn handle-matrix-right-click []
  (fn [event-data]
    (log/info "Wireless Matrix right-clicked!")
    (let [{:keys [player world pos sneaking]} event-data
          tile (get-matrix-tile pos)]
      (if tile
        (if-not sneaking
          (do
            (log/info "Opening Matrix GUI")
            (log/info "  Plates:" (get-plate-count tile))
            (log/info "  Core Level:" (get-core-level tile))
            (log/info "  Working:" (is-working? tile))
            (log/info "  Capacity:" (winterfaces/get-matrix-capacity tile))
            (log/info "  Bandwidth:" (winterfaces/get-matrix-bandwidth tile))
            (log/info "  Range:" (winterfaces/get-matrix-range tile))
            ;; TODO: Open GUI
            )
          (log/info "Sneaking - no action"))
        (log/info "No tile entity found!")))))

(defn handle-matrix-place []
  (fn [event-data]
    (log/info "Placing Wireless Matrix")
    (let [{:keys [player world pos]} event-data
          player-name (str player)
          tickable-tile (create-tickable-matrix-tile-entity world pos)
          tile-data (get-tile-data tickable-tile)]
      ;; Set placer
      (assoc tile-data :placer-name player-name)
      ;; Register tickable tile entity
      (register-matrix-tile! pos tickable-tile)
      (log/info "Matrix placed by" player-name "at" pos)
      (log/info "Tickable TileEntity registered"))))

(defn handle-matrix-break []
  (fn [event-data]
    (log/info "Breaking Wireless Matrix")
    (let [{:keys [world pos]} event-data
          tile (get-matrix-tile pos)]
      (when tile
        ;; Drop items from inventory
        (doseq [[idx item] (map-indexed vector @(:inventory tile))]
          (when item
            (log/info "Dropping item from slot" idx ":" item)))
        ;; Unregister tile entity
        (unregister-matrix-tile! pos)))))

;; ============================================================================
;; Block Definition
;; ============================================================================

(bdsl/defblock wireless-matrix
  :material :rock
  :hardness 3.0
  :resistance 6.0
  :requires-tool true
  :harvest-tool :pickaxe
  :harvest-level 1
  :light-level 1.0
  :sounds :stone
  :multi-block {:positions [[0 0 1] [1 0 1] [1 0 0]
                            [0 1 0] [0 1 1] [1 1 1] [1 1 0]]
                :rotation-center [1.0 0 1.0]}
  :on-right-click (handle-matrix-right-click)
  :on-place (handle-matrix-place)
  :on-break (handle-matrix-break))

;; ============================================================================
;; Initialization
;; ============================================================================

(defn init-wireless-matrix! []
  (log/info "Initialized Wireless Matrix:")
  (log/info "  - 2x2x2 multiblock structure")
  (log/info "  - 4 inventory slots")
  (log/info "  - Capacity: 8 * coreLevel")
  (log/info "  - Bandwidth: coreLevel² * 60")
  (log/info "  - Range: 24 * √coreLevel"))

(defn tick-all-matrices! []
  "Tick all registered matrices"
  (doseq [[pos tile] @matrix-tiles]
    (if (instance? TileMatrixTickable tile)
      (tile)
      (update-matrix-tile! tile))))

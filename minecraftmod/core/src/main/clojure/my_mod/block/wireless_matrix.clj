(ns my-mod.block.wireless-matrix
  "Wireless Matrix block implementation - 2x2x2 multiblock structure
  
  Core component of wireless energy network providing capacity, bandwidth and range."
  (:require [my-mod.block.dsl :as bdsl]
            [my-mod.block.tile-logic :as tile-logic]
            [my-mod.wireless.interfaces :as winterfaces]
            [my-mod.inventory.core :as inv]
            [my-mod.nbt.dsl :as nbt]
            [my-mod.item.constraint-plate :as plate]
            [my-mod.item.mat-core :as core]
            [my-mod.wireless.gui.matrix-sync :as sync]
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

  (declare recalculate-plate-count!)
  (declare sync-to-clients! verify-structure! unregister-matrix-tile!)

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
;; Java Accessor Bridge (for XML GUI Integration)
;; ============================================================================

(definterface IMatrixJavaProxy
  (^String getPlacerName [])
  (^long getMatrixCapacity [])
  (^long getMatrixBandwidth [])
  (^double getMatrixRange [])
  (^long getLoad [])
  (^Object getPos []))

(deftype MatrixJavaProxy
  [tile-entity]
  
  IMatrixJavaProxy
  
  (^String getPlacerName [this]
    (:placer-name tile-entity))
  
  (^long getMatrixCapacity [this]
    (long (winterfaces/get-matrix-capacity tile-entity)))
  
  (^long getMatrixBandwidth [this]
    (long (winterfaces/get-matrix-bandwidth tile-entity)))
  
  (^double getMatrixRange [this]
    (double (winterfaces/get-matrix-range tile-entity)))
  
  (^long getLoad [this]
    0)
  
  (^Object getPos [this]
    (:pos tile-entity))
  
  Object
  (toString [this]
    (str "MatrixJavaProxy[" (:pos tile-entity) "]")))

;; ============================================================================
;; IInventory Protocol Implementation
;; ============================================================================

(extend-protocol inv/IInventory
  TileMatrix
  
  (get-size-inventory [this]
    4)  ; 4 slots: 3 plates + 1 core
  
  (get-stack-in-slot [this slot]
    (get-inventory-slot this slot))
  
  (decr-stack-size [this slot count]
    (when-let [stack (get-inventory-slot this slot)]
      (let [stack-count (.getCount stack)]
        (if (<= stack-count count)
          ;; Remove entire stack
          (let [result stack]
            (set-inventory-slot! this slot nil)
            result)
          ;; Split stack
          (let [result (.splitStack stack count)]
            result)))))
  
  (remove-stack-from-slot [this slot]
    (let [stack (get-inventory-slot this slot)]
      (set-inventory-slot! this slot nil)
      stack))
  
  (set-inventory-slot-contents [this slot stack]
    (set-inventory-slot! this slot stack))
  
  (get-inventory-stack-limit [this]
    1)  ; Matrix: max 1 item per slot
  
  (is-usable-by-player? [this player]
    true)
  
  (is-item-valid-for-slot? [this slot stack]
    (is-item-valid-for-slot? this slot stack))  ; Use existing function
  
  (get-inventory-name [this]
    "wireless_matrix")
  
  (has-custom-name? [this]
    false))

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
  "Synchronize matrix state to nearby clients
  
  Sends matrix state data via network packet to players tracking this tile.
  Called every 15 ticks (approximately once per second)."
  [tile]
  (let [world (:world tile)
        pos (:pos tile)
        ;; Construct sync payload
        sync-data {:pos-x (.getX pos)
                   :pos-y (.getY pos)
                   :pos-z (.getZ pos)
                   :plate-count (get-plate-count tile)
                   :placer-name (:placer-name tile)
                   :is-working (is-working? tile)
                   :core-level (get-core-level tile)
                   :capacity (winterfaces/get-matrix-capacity tile)
                   :bandwidth (winterfaces/get-matrix-bandwidth tile)
                   :range (winterfaces/get-matrix-range tile)}]
    ;; Send to nearby players via platform network system
    (try
      ;; Platform-specific sync (defined in each platform's network module)
      (require 'my-mod.wireless.gui.matrix-sync) ; Dynamic require
      ((resolve 'my-mod.wireless.gui.matrix-sync/broadcast-matrix-state) 
       world pos sync-data)
      (log/debug "Matrix state synced:" sync-data)
      (catch Exception e
        (log/debug "Matrix sync not yet implemented:" (.getMessage e))))))

(defn verify-structure!
  "Verify multiblock structure integrity
  Destroys block if structure is broken"
  [tile]
  ;; Only check on origin block (sub-id = 0)
  (when (and tile (= (:sub-id tile) 0))
    (try
      (let [world (:world tile)
            pos (:pos tile)
            ;; Get block spec for multiblock config
            block-spec (bdsl/get-block :wireless-matrix)]

        (when (and block-spec world pos)
          ;; Check if structure is still valid
          (if-not (bdsl/is-multi-block-complete? world pos block-spec)
            ;; Structure broken - destroy matrix and drop items
            (do
              (log/info "Matrix structure broken at" pos)
              ;; Drop inventory items
              (doseq [[idx item] (map-indexed vector @(:inventory tile))]
                (when item
                  (log/info "Dropping item from slot" idx)))
              ;; Unregister from tiles
              (unregister-matrix-tile! pos))
            ;; Structure intact - ok
            (log/debug "Matrix structure verified at" pos))))
      
      (catch Exception e
        (log/error "Error verifying matrix structure:" (.getMessage e))))))

;; ============================================================================
;; NBT Persistence (using NBT DSL)
;; ============================================================================

;; Define NBT serialization using declarative DSL
(nbt/defnbt matrix
  ;; Placer name (direct field access)
  [:placer-name "placer" :string]
  
  ;; Plate count (atom of integer)
  [:plate-count "plateCount" :int :atom? true]
  
  ;; Sub ID (direct field access)
  [:sub-id "subId" :int]
  
  ;; Direction (keyword - needs conversion)
  [:direction "direction" :keyword]
  
  ;; Inventory (uses inventory protocol)
  [:inventory "inventory" :inventory])

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

(defn- ensure-matrix-tile-for-be!
  "Compatibility adapter: materialize legacy TileMatrix from scripted BE context."
  [world pos]
  (or (get-matrix-tile pos)
      (let [tile (create-matrix-tile-entity world pos)]
        (register-matrix-tile! pos tile)
        tile)))

(defn matrix-scripted-tick-fn
  [level pos _state be]
  (let [tile (ensure-matrix-tile-for-be! level pos)]
    (when tile
      (update-matrix-tile! tile)
      (.setScriptData be "placer-name" (:placer-name tile))
      (.setScriptData be "plate-count" (long (get-plate-count tile)))
      (.setScriptData be "core-level" (long (get-core-level tile)))
      (.setScriptData be "working" (boolean (is-working? tile)))
      (.setChanged be))))

(defn matrix-scripted-load-fn
  [tag]
  {"placer-name" (if (.contains tag "Placer") (.getString tag "Placer") "")
   "plate-count" (if (.contains tag "PlateCount") (.getInt tag "PlateCount") 0)
   "core-level" (if (.contains tag "CoreLevel") (.getInt tag "CoreLevel") 0)})

(defn matrix-scripted-save-fn
  [be tag]
  (when-let [placer (.getScriptData be "placer-name")]
    (.putString tag "Placer" (str placer)))
  (when-let [plate-count (.getScriptData be "plate-count")]
    (.putInt tag "PlateCount" (int plate-count)))
  (when-let [core-level (.getScriptData be "core-level")]
    (.putInt tag "CoreLevel" (int core-level))))

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
          tile (or (get-matrix-tile pos)
                   (ensure-matrix-tile-for-be! world pos))]
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
            ;; Open GUI
            (try
              (if-let [open-matrix-gui (requiring-resolve 'my-mod.wireless.gui.registry/open-matrix-gui)]
                (do
                  (open-matrix-gui player world pos)
                  (log/info "Opened Matrix GUI"))
                (log/error "Failed to open Matrix GUI: open-matrix-gui not resolved"))
              (catch Exception e
                (log/error "Failed to open Matrix GUI:" (.getMessage e)))))
          (log/info "Sneaking - no action"))
        (log/info "No tile entity found!")))))

(defn handle-matrix-place []
  (fn [event-data]
    (log/info "Placing Wireless Matrix")
    (let [{:keys [player world pos]} event-data
          player-name (str player)
          tile-data (ensure-matrix-tile-for-be! world pos)]
      ;; Compatibility registration for GUI/network adapters
      (swap! matrix-tiles assoc pos (assoc tile-data :placer-name player-name))
      (log/info "Matrix placed by" player-name "at" pos)
      (log/info "Matrix compatibility tile registered"))))

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
  :registry-name "matrix"
  :material :stone
  :hardness 3.0
  :resistance 6.0
  :requires-tool true
  :harvest-tool :pickaxe
  :harvest-level 1
  :light-level 1.0
  :sounds :stone
  :has-block-entity? true
  :tile-kind :wireless-matrix
  :tile-tick-fn matrix-scripted-tick-fn
  :tile-load-fn matrix-scripted-load-fn
  :tile-save-fn matrix-scripted-save-fn
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
  (tile-logic/register-tile-kind! :wireless-matrix
                                   {:tick-fn matrix-scripted-tick-fn
                                    :read-nbt-fn matrix-scripted-load-fn
                                    :write-nbt-fn matrix-scripted-save-fn})
  (log/info "Initialized Wireless Matrix:")
  (log/info "  - 2x2x2 multiblock structure")
  (log/info "  - 4 inventory slots")
  (log/info "  - Capacity: 8 * coreLevel")
  (log/info "  - Bandwidth: coreLevel² * 60")
  (log/info "  - Range: 24 * √coreLevel"))

(defn tick-all-matrices! []
  "Compatibility no-op after migration to scripted BE tick path."
  nil)

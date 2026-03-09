(ns my-mod.wireless.gui.matrix-container
  "Wireless Matrix GUI Container - handles 4 slots and multiblock data sync.

  State model (Design-3): tile-entity is a ScriptedBlockEntity; all slot data
  is read from / written to (.getCustomState be) / (.setCustomState be new-state)."
  (:require [my-mod.util.log :as log]
            [my-mod.block.wireless-matrix :as wm]
            [my-mod.item.constraint-plate :as plate]
            [my-mod.item.mat-core :as core]
            [my-mod.wireless.world-data :as wd]
            [my-mod.wireless.virtual-blocks :as vb]
            [my-mod.wireless.gui.container-common :as common]
            [my-mod.wireless.gui.container-move-common :as move-common
             :refer [defquick-move-stack-config]]
            [my-mod.wireless.gui.sync-helpers :as sync-helpers]))

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
   range              ; atom<double> - Network range
   sync-ticker])      ; atom<int> - tick counter for network sync (5s timeout)

;; ============================================================================
;; Container Creation
;; ============================================================================

(defn- resolve-state
  "Resolve the state map from either a ScriptedBlockEntity or an existing map.
  Returns [be state] where be may be nil for legacy map input."
  [tile]
  (if (map? tile)
    [nil tile]
    (try
      (let [state (or (.getCustomState tile) wm/default-state)]
        [tile state])
      (catch Exception e
        (log/warn "Could not resolve customState from BE:" (.getMessage e))
        [tile {}]))))

(defn create-container
  "Create a Matrix GUI container instance.

  Args:
  - tile: ScriptedBlockEntity or legacy Clojure map
  - player: Player who opened GUI

  Returns: MatrixContainer record"
  [tile player]
  (let [[be state] (resolve-state tile)
        proxy      (if be
                     (wm/->MatrixJavaProxy be)
                     (wm/->MatrixJavaProxy tile))]
    (->MatrixContainer
      (or be tile)
      proxy
      player
      (atom (:core-level state 0))
      (atom (:plate-count state 0))
      (atom (wm/is-working? state))
      (atom (wm/get-matrix-capacity state))
      (atom (wm/get-matrix-capacity state))
      (atom (long (wm/get-matrix-bandwidth state)))
      (atom (double (wm/get-matrix-range state)))
      (atom 0))))

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
  "Get item from slot. Reads from BE customState if tile-entity is a BE."
  [container slot-index]
  (let [tile (:tile-entity container)]
    (if (map? tile)
      (common/get-slot-item container slot-index)
      (try
        (get-in (.getCustomState tile) [:inventory slot-index])
        (catch Exception _ (common/get-slot-item container slot-index))))))

(defn set-slot-item!
  "Set item in slot. Writes to BE customState if tile-entity is a BE."
  [container slot-index item-stack]
  (let [tile (:tile-entity container)]
    (if (map? tile)
      (common/set-slot-item! container slot-index item-stack)
      (try
        (let [state  (or (.getCustomState tile) wm/default-state)
              state' (-> state
                         (assoc-in [:inventory slot-index] item-stack)
                         wm/recalculate-counts)]
          (.setCustomState tile state'))
        (catch Exception _
          (common/set-slot-item! container slot-index item-stack))))))

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
  
  Called every tick on server side. Network capacity queries are throttled 
  to every 5 seconds (100 ticks) to reduce performance impact."
  [container]
  (let [tile (:tile-entity container)
        
        ;; Count components (every tick - lightweight)
        plates (count-plates container)
        core-lvl (get-core-level container)
        
        ;; Check if multiblock is formed
        working? (and (> core-lvl 0)
                     (>= plates 0))] ; At least a core is required
    
    ;; Update basic data (every tick)
    (reset! (:core-level container) core-lvl)
    (reset! (:plate-count container) plates)
    (reset! (:is-working container) working?)
    
    ;; Update network capacity and stats (throttled to every 100 ticks = 5 seconds)
    (sync-helpers/with-throttled-sync! (:sync-ticker container) 100
      (fn []
        ;; Calculate stats based on components
        (let [stats (calculate-matrix-stats core-lvl plates)]
          (reset! (:bandwidth container) (:bandwidth stats))
          (reset! (:range container) (:range stats))
          ;; Query actual network capacity from WirelessNet
          (sync-helpers/query-matrix-network-capacity! container stats))))))

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

(defn apply-sync-data!
  "Apply sync data from server to container atoms.
  
  Args:
  - container: MatrixContainer instance
  - data: Map of synced values (from get-sync-data)
  
  Side effects: Updates all container atoms from data"
  [container data]
  (doseq [[k v] data]
    (when-let [atom-ref (get container k)]
      (reset! atom-ref v))))

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
  (common/still-valid? container player))

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

(defquick-move-stack-config quick-move-stack
  {:container-slots #{slot-plate-1 slot-plate-2 slot-plate-3 slot-core}
   :inventory-pred (fn [slot-index player-inventory-start]
                     (>= slot-index player-inventory-start))
   :rules [{:accept? (fn [item] (can-place-item? container slot-core item))
            :slots [slot-core]}
           {:accept? (fn [item] (can-place-item? container slot-plate-1 item))
            :slots [slot-plate-1 slot-plate-2 slot-plate-3]}]})

;; ============================================================================
;; Container Lifecycle
;; ============================================================================

(defn on-close
  "Cleanup when container is closed
  
  Args:
  - container: MatrixContainer instance
  
  Returns: nil"
  [container]
  (log/debug "Closing wireless matrix container")
  (common/reset-container-atoms!
    [(:core-level container) 0]
    [(:plate-count container) 0]
    [(:is-working container) false]
    [(:capacity container) 0]
    [(:max-capacity container) 0]
    [(:bandwidth container) 0]
    [(:range container) 0.0]
    [(:sync-ticker container) 0]))

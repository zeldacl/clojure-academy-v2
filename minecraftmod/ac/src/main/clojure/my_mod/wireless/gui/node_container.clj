(ns my-mod.wireless.gui.node-container
  "Wireless Node GUI Container - handles server-side inventory and data sync"
  (:require [my-mod.wireless.interfaces :as winterfaces]
            [my-mod.energy.operations :as energy-stub]
            [my-mod.wireless.world-data :as wd]
            [my-mod.wireless.virtual-blocks :as vb]
            [my-mod.inventory.core :as inv]
            [my-mod.wireless.gui.container-common :as common]
            [my-mod.wireless.gui.container-move-common :as move-common
             :refer [defquick-move-stack-config]]
            [my-mod.wireless.gui.sync-helpers :as sync-helpers]
            [my-mod.util.log :as log]))

;; ============================================================================
;; Container Data Structure
;; ============================================================================

(defrecord NodeContainer
  [tile-entity        ; NodeTileEntity reference
   player             ; Player who opened GUI
   
   ;; Synced data (updated from server -> client)
   energy             ; atom<int> - current energy
   max-energy         ; atom<int> - maximum energy
   node-type          ; atom<keyword> - :basic/:standard/:advanced
   is-online          ; atom<boolean> - connected to network?
   ssid               ; atom<string> - network name
   password           ; atom<string> - network password
   transfer-rate      ; atom<int> - current IF/t transfer
   capacity           ; atom<int> - current network node count
   max-capacity       ; atom<int> - maximum network capacity
   charge-ticker      ; atom<int> - tick counter for charging
   sync-ticker])      ; atom<int> - tick counter for network sync (5s timeout)

;; ============================================================================
;; Container Creation
;; ============================================================================

(defn- resolve-tile
  "If tile is a Java ScriptedBlockEntity (not a Clojure map), look up the
  corresponding Clojure TileNode from the block's registry using its BlockPos."
  [tile]
  (if (map? tile)
    tile
    (try
      (let [pos (.getBlockPos tile)
            get-node-tile (requiring-resolve 'my-mod.block.wireless-node/get-node-tile)]
        (or (get-node-tile pos)
            (do (log/warn "No Clojure TileNode found at" pos "- using raw BE") tile)))
      (catch Exception e
        (log/warn "Could not resolve TileNode from Java BE:" (.getMessage e))
        tile))))

(defn create-container
  "Create a Node GUI container instance
  
  Args:
  - tile: NodeTileEntity instance (Clojure map or Java ScriptedBlockEntity)
  - player: Player who opened GUI
  
  Returns: NodeContainer record"
  [tile player]
  (let [tile (resolve-tile tile)]
    (->NodeContainer
      tile
      player
    ;; Initialize synced data from tile
    (atom (int (winterfaces/get-energy tile)))
    (atom (int (winterfaces/get-max-energy tile)))
    (atom (:node-type tile))
    (atom @(:enabled tile))
    (atom (winterfaces/get-node-name tile))
    (atom (winterfaces/get-password tile))
    (atom 0)      ; Transfer rate computed on update
    (atom 0)      ; Capacity - network node count
    (atom 0)      ; Max capacity - from matrix
    (atom 0)      ; Charge ticker - for throttling charging
    (atom 0))))   ; Sync ticker - for throttling network sync

;; ============================================================================
;; Slot Management
;; ============================================================================

(def slot-input 0)
(def slot-output 1)

(defn get-slot-count
  "Get total slot count (2 for node)"
  [_container]
  2)

(defn get-owner
  "Get node owner name"
  [container]
  (:placer-name (:tile-entity container)))

(defn can-place-item?
  "Check if item can be placed in slot
  
  Slot 0 (input): Only items with energy capability
  Slot 1 (output): No direct placement (output only)"
  [container slot-index item-stack]
  (case slot-index
    0 (energy-stub/is-energy-item-supported? item-stack)
    1 false ; Output slot cannot be placed into
    false))

(defn get-slot-item
  "Get item from slot"
  [container slot-index]
  (common/get-slot-item container slot-index))

(defn set-slot-item!
  "Set item in slot"
  [container slot-index item-stack]
  (common/set-slot-item! container slot-index item-stack))

(defn slot-changed!
  "Called when slot contents change"
  [container slot-index]
  (log/info "Node container slot" slot-index "changed"))

;; ============================================================================
;; Data Synchronization
;; ============================================================================

(defn sync-to-client!
  "Update container data from tile entity (server -> client)
  
  Called every tick on server side. Network capacity queries are throttled 
  to every 5 seconds (100 ticks) to reduce performance impact."
  [container]
  (let [tile (:tile-entity container)]
    ;; Update energy (every tick)
    (reset! (:energy container) (int (winterfaces/get-energy tile)))
    (reset! (:max-energy container) (int (winterfaces/get-max-energy tile)))
    
    ;; Update connection status (every tick)
    (reset! (:is-online container) @(:enabled tile))
    
    ;; Update node info (every tick)
    (reset! (:ssid container) (winterfaces/get-node-name tile))
    (reset! (:password container) (winterfaces/get-password tile))
    
    ;; Update network capacity info (throttled to every 100 ticks = 5 seconds)
    (sync-helpers/with-throttled-sync! (:sync-ticker container) 100
      (fn []
        (sync-helpers/query-node-network-capacity! container)))
    
    ;; Compute transfer rate (charging speed)
    (let [charging-in? @(:charging-in tile)
          charging-out? @(:charging-out tile)
          rate (cond
             (and charging-in? charging-out?) 200 ; Bidirectional
             charging-in? 100  ; Charging from slot
             charging-out? 100 ; Charging to slot
                 :else 0)]
      (reset! (:transfer-rate container) rate))))

(defn get-sync-data
  "Get data to sync to client
  
  Returns: Map of synced values"
  [container]
  {:energy @(:energy container)
   :max-energy @(:max-energy container)
   :node-type @(:node-type container)
   :is-online @(:is-online container)
   :ssid @(:ssid container)
   :password @(:password container)
   :transfer-rate @(:transfer-rate container)
   :capacity @(:capacity container)
   :max-capacity @(:max-capacity container)})

(defn apply-sync-data!
  "Apply sync data from server to container atoms.
  
  Args:
  - container: NodeContainer instance
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
  - container: NodeContainer instance
  - player: Player instance
  
  Returns: boolean"
  [container player]
  (common/still-valid? container player))

;; ============================================================================
;; Container Update Tick
;; ============================================================================

(defn tick!
  "Called every tick on server side
  
  Updates synced data and handles slot charging logic"
  [container]
  ;; Sync data to client
  (sync-to-client! container)
  
  ;; Increment charge ticker
  (swap! (:charge-ticker container) inc)
  
  ;; Only perform charging operations every 10 ticks (0.5 seconds)
  ;; This prevents overly fast energy transfer
  (when (>= @(:charge-ticker container) 10)
    (reset! (:charge-ticker container) 0)
    
    ;; Handle item charging in input slot
    (let [tile (:tile-entity container)
          input-item (get-slot-item container slot-input)
          output-item (get-slot-item container slot-output)]
      
      ;; Charge items from node energy
      (when (and output-item
                 (energy-stub/is-energy-item-supported? output-item)
                 (> (winterfaces/get-energy tile) 0))
        (let [to-give (min 100 (winterfaces/get-energy tile))
              pulled (energy-stub/pull-from-node tile to-give false)
              leftover (energy-stub/charge-energy-to-item output-item pulled false)
              given (- pulled leftover)]
          (when (> leftover 0)
            (energy-stub/charge-node tile leftover false))
          (reset! (:charging-out tile) (> given 0))))
      
      ;; Charge node from items
      (when (and input-item
                 (energy-stub/is-energy-item-supported? input-item)
                 (< (winterfaces/get-energy tile) (winterfaces/get-max-energy tile)))
        (let [to-take (min 100 (- (winterfaces/get-max-energy tile)
                                   (winterfaces/get-energy tile)))
              taken (energy-stub/pull-energy-from-item input-item to-take false)
              leftover (energy-stub/charge-node tile taken false)
              accepted (- taken leftover)]
          (when (> leftover 0)
            (energy-stub/charge-energy-to-item input-item leftover false))
          (reset! (:charging-in tile) (> accepted 0)))))))

;; ============================================================================
;; Button Actions
;; ============================================================================

(def button-toggle-connection 0)
(def button-set-ssid 1)
(def button-set-password 2)

(defn handle-button-click!
  "Handle button click from client
  
  Args:
  - container: NodeContainer instance
  - button-id: int button ID
  - data: optional data map from client"
  [container button-id data]
  (let [tile (:tile-entity container)]
    (case button-id
      0 ; Toggle connection
      (do
        (swap! (:enabled tile) not)
        (log/info "Toggled node connection:" @(:enabled tile)))
      
      1 ; Set SSID
      (when-let [new-ssid (:ssid data)]
        (let [new-tile (assoc tile :node-name new-ssid)]
          (log/info "Set node SSID to:" new-ssid)
          ;; In real implementation, update tile entity
          ))
      
      2 ; Set password
      (when-let [new-password (:password data)]
        (let [new-tile (assoc tile :password new-password)]
          (log/info "Set node password")
          ;; In real implementation, update tile entity
          ))
      
      (log/warn "Unknown button ID:" button-id))))

;; ============================================================================
;; Quick Move (Shift+Click)
;; ============================================================================

(defquick-move-stack-config quick-move-stack
  {:container-slots #{slot-input slot-output}
   :inventory-pred (fn [slot-index player-inventory-start]
                     (>= slot-index player-inventory-start))
  :rules [{:accept? (fn [item] (energy-stub/is-energy-item-supported? item))
            :slots [slot-input]}]})

;; ============================================================================
;; Container Lifecycle
;; ============================================================================

(defn on-close
  "Cleanup when container is closed
  
  Args:
  - container: NodeContainer instance
  
  Returns: nil"
  [container]
  (log/debug "Closing wireless node container")
  (common/reset-container-atoms!
    [(:energy container) 0]
    [(:max-energy container) 0]
    [(:is-online container) false]
    [(:transfer-rate container) 0]
    [(:capacity container) 0]
    [(:max-capacity container) 0]
    [(:charge-ticker container) 0]
    [(:sync-ticker container) 0]))

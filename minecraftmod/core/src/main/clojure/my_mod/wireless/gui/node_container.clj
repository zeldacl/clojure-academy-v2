(ns my-mod.wireless.gui.node-container
  "Wireless Node GUI Container - handles server-side inventory and data sync"
  (:require [my-mod.wireless.interfaces :as winterfaces]
            [my-mod.wireless.world-data :as wd]
            [my-mod.wireless.virtual-blocks :as vb]
            [my-mod.inventory.core :as inv]
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

(defn create-container
  "Create a Node GUI container instance
  
  Args:
  - tile: NodeTileEntity instance
  - player: Player who opened GUI
  
  Returns: NodeContainer record"
  [tile player]
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
    (atom 0)))    ; Sync ticker - for throttling network sync

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
    0 (winterfaces/has-energy-capability? item-stack)
    1 false ; Output slot cannot be placed into
    false))

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
    (swap! (:sync-ticker container) inc)
    (when (>= @(:sync-ticker container) 100)
      (reset! (:sync-ticker container) 0)
      (try
        (let [world (:world tile)
              pos (:pos tile)
              node-vblock (vb/create-vnode (.getX pos) (.getY pos) (.getZ pos))
              world-data (wd/get-world-data world)
              network (wd/get-network-by-node world-data node-vblock)]
          (if network
            (do
              (reset! (:capacity container) (count @(:nodes network)))
              ;; Get matrix capacity
              (when-let [matrix-vb (:matrix network)]
                (when-let [matrix (vb/vblock-get matrix-vb world)]
                  (reset! (:max-capacity container) 
                         (try (winterfaces/get-capacity matrix) (catch Exception _ 0))))))
            (do
              (reset! (:capacity container) 0)
              (reset! (:max-capacity container) 0))))
        (catch Exception e
          (reset! (:capacity container) 0)
          (reset! (:max-capacity container) 0))))
    
    ;; Compute transfer rate (charging speed)
    (let [charging-in? @(:charging-in tile)
          charging-out? @(:charging-out tile)
          rate (cond
                 (and charging-in charging-out) 200 ; Bidirectional
                 charging-in 100  ; Charging from slot
                 charging-out 100 ; Charging to slot
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
                 (winterfaces/has-energy-capability? output-item)
                 (> (winterfaces/get-energy tile) 0))
        (let [to-give (min 100 (winterfaces/get-energy tile))
              given (winterfaces/give-energy output-item to-give false)]
          (winterfaces/take-energy tile given false)
          (reset! (:charging-out tile) (> given 0))))
      
      ;; Charge node from items
      (when (and input-item
                 (winterfaces/has-energy-capability? input-item)
                 (< (winterfaces/get-energy tile) (winterfaces/get-max-energy tile)))
        (let [to-take (min 100 (- (winterfaces/get-max-energy tile)
                                   (winterfaces/get-energy tile)))
              taken (winterfaces/take-energy input-item to-take false)]
          (winterfaces/give-energy tile taken false)
          (reset! (:charging-in tile) (> taken 0)))))))

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

(defn quick-move-stack
  "Handle shift-click on slot
  
  Logic:
  - Input slot -> Player inventory
  - Player inventory -> Input slot (if has energy capability)
  
  Returns: ItemStack or nil"
  [container slot-index player-inventory-start]
  (cond
    ;; From input slot to player
    (= slot-index slot-input)
    (let [item (get-slot-item container slot-input)]
      (when item
        ;; Move to player inventory
        (set-slot-item! container slot-input nil)
        item))
    
    ;; From output slot to player
    (= slot-index slot-output)
    (let [item (get-slot-item container slot-output)]
      (when item
        (set-slot-item! container slot-output nil)
        item))
    
    ;; From player inventory to input slot
    (>= slot-index player-inventory-start)
    (let [item (get-slot-item container slot-index)]
      (if (and item (winterfaces/has-energy-capability? item))
        (if (nil? (get-slot-item container slot-input))
          ;; Successfully moved to input slot
          (do
            (set-slot-item! container slot-input item)
            (set-slot-item! container slot-index nil)
            nil)  ; Return nil to indicate successful transfer
          ;; Input slot occupied, return original item
          item)
        ;; Not an energy item, return it unchanged
        item))
    
    :else nil))

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
  ;; Reset all atoms to default states
  (reset! (:energy container) 0)
  (reset! (:max-energy container) 0)
  (reset! (:is-online container) false)
  (reset! (:transfer-rate container) 0)
  (reset! (:capacity container) 0)
  (reset! (:max-capacity container) 0)
  (reset! (:charge-ticker container) 0)
  (reset! (:sync-ticker container) 0)
  nil)

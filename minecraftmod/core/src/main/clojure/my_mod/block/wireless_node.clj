(ns my-mod.block.wireless-node
  "Wireless Node block implementation - energy network node with item charging
  
  Implements ITickable interface for automatic updates every game tick."
  (:require [my-mod.block.dsl :as bdsl]
            [my-mod.energy.stub :as energy]
            [my-mod.wireless.interfaces :as winterfaces]
            [my-mod.inventory.core :as inv]
            [my-mod.nbt.dsl :as nbt]
            [my-mod.wireless.world-data :as wd]
            [my-mod.wireless.virtual-blocks :as vb]
            [my-mod.util.log :as log]))

;; Node type specifications
(def node-types
  {:basic {:max-energy 15000
           :bandwidth 150
           :range 9
           :capacity 5}
   :standard {:max-energy 50000
              :bandwidth 300
              :range 12
              :capacity 10}
   :advanced {:max-energy 200000
              :bandwidth 900
              :range 19
              :capacity 20}})

;; BlockState properties
;; ENERGY: 0-4 (energy level indicator)
;; CONNECTED: true/false (network connection status)
(def block-state-properties
  {:energy {:name "energy"
            :type :integer
            :min 0
            :max 4
            :default 0}
   :connected {:name "connected"
               :type :boolean
               :default false}})

;; TileEntity state - using atom to hold mutable state
(defrecord NodeTileEntity 
  [;; Identity
   node-type          ; :basic, :standard, or :advanced
  node-name          ; atom<String> - node name
  password           ; atom<String> - network password
  placer-name        ; String - who placed this node
   
   ;; Energy
   energy             ; atom<double> - current energy
   
   ;; Inventory (2 slots)
   inventory          ; atom<vector> - [input-slot output-slot]
   
   ;; Status flags
   enabled            ; atom<boolean> - connected to network?
   charging-in        ; atom<boolean> - charging from input slot?
   charging-out       ; atom<boolean> - charging to output slot?
   
   ;; Update counter
   update-ticker      ; atom<int> - tick counter for periodic updates
   
   ;; Position
   world              ; World object
   pos])              ; BlockPos

(defn create-node-tile-entity
  "Create a new node tile entity"
  [node-type world pos]
  (->NodeTileEntity
    node-type
    (atom "Unnamed")
    (atom "")
    ""
    (atom 0.0)
    (atom [nil nil])  ; [input-slot output-slot]
    (atom false)
    (atom false)
    (atom false)
    (atom 0)
    world
    pos))

;; Additional helper functions for node management
(defn set-node-name! [tile name]
  (if (instance? clojure.lang.IDeref (:node-name tile))
    (do (reset! (:node-name tile) name) tile)
    (assoc tile :node-name name)))

(defn set-password-str! [tile password]
  (if (instance? clojure.lang.IDeref (:password tile))
    (do (reset! (:password tile) password) tile)
    (assoc tile :password password)))

(defn- deref-if-atom [value]
  (if (instance? clojure.lang.IDeref value) @value value))

(declare get-inventory-slot set-inventory-slot!)

;; ============================================================================
;; BlockState Management
;; ============================================================================

(defn calculate-energy-level
  "Calculate energy level (0-4) based on current energy percentage"
  [tile]
  (let [current (winterfaces/get-energy tile)
        max-energy (winterfaces/get-max-energy tile)
        percentage (/ current max-energy)]
    (cond
      (<= percentage 0.0) 0
      (<= percentage 0.25) 1
      (<= percentage 0.50) 2
      (<= percentage 0.75) 3
      :else 4)))

(defn rebuild-block-state!
  "Update block state based on TileEntity data
  
  Updates:
  - ENERGY property (0-4) based on energy percentage
  - CONNECTED property based on network connection status
  
  Parameters:
  - tile: NodeTileEntity instance
  
  Returns: true if state was updated, false otherwise"
  [tile]
  (try
    (when-let [world (:world tile)]
      (when-let [pos (:pos tile)]
        (let [current-state (.getBlockState world pos)
              energy-level (calculate-energy-level tile)
              connected @(:enabled tile)]
          
          ;; Try to update actual block state with proper API detection
          (try
            ;; Attempt to get block state properties and update them
            ;; This will work when running in actual Minecraft environment
            (when-let [block (.getBlock current-state)]
              (let [new-state (-> current-state
                                ;; Try to set ENERGY property if it exists
                                (as-> state
                                  (try (.setValue state 
                                         (.getProperty block "energy")
                                         (Integer/valueOf energy-level))
                                       (catch Exception _ state)))
                                ;; Try to set CONNECTED property if it exists  
                                (as-> state
                                  (try (.setValue state
                                         (.getProperty block "connected")
                                         (Boolean/valueOf connected))
                                       (catch Exception _ state))))]
                ;; Only update if state actually changed
                (when-not (= new-state current-state)
                  (.setBlock world pos new-state 3)
                  (log/debug "Updated block state at" pos
                            "energy:" energy-level "connected:" connected))))
            (catch Exception e
              ;; Gracefully handle when BlockState API is not available
              (log/debug "Block state update not available:" (.getMessage e))))
          
          true)))
    (catch Exception e
      (log/error "Failed to rebuild block state:" (.getMessage e))
      false)))

(defn get-actual-state
  "Get the actual block state for rendering
  
  Reads current TileEntity state and returns appropriate BlockState.
  Called by Minecraft rendering system.
  
  Parameters:
  - world: World instance
  - pos: BlockPos instance
  - base-state: Base IBlockState
  
  Returns: IBlockState with updated properties"
  [world pos base-state]
  (try
    ;; Try to get tile entity from world
    (if-let [tile-entity (.getBlockEntity world pos)]
      ;; Check if it's our NodeTileEntity type
      (if (and (map? tile-entity) (:node-type tile-entity))
        (let [tile tile-entity
              energy-level (calculate-energy-level tile)
              connected @(:enabled tile)
              block (.getBlock base-state)]
          
          (log/debug "Getting actual state for" pos
                    "energy:" energy-level
                    "connected:" connected)
          
          ;; Try to update block state with properties
          (try
            (-> base-state
              ;; Try to set ENERGY property
              (as-> state
                (try (.setValue state (.getProperty block "energy") 
                       (Integer/valueOf energy-level))
                     (catch Exception _ state)))
              ;; Try to set CONNECTED property
              (as-> state
                (try (.setValue state (.getProperty block "connected")
                       (Boolean/valueOf connected))
                     (catch Exception _ state))))
            (catch Exception e
              (log/debug "Block property update not available:" (.getMessage e))
              base-state)))
        base-state)
      base-state)
    (catch Exception e
      (log/warn "Failed to get actual state:" (.getMessage e))
      base-state)))

;; ============================================================================
;; IWirelessNode Protocol Implementation
;; ============================================================================

(extend-protocol winterfaces/IWirelessNode
  NodeTileEntity
  
  (get-max-energy [this]
    (get-in node-types [(:node-type this) :max-energy]))
  
  (get-energy [this]
    @(:energy this))
  
  (set-energy [this energy]
    (reset! (:energy this) 
            (max 0.0 (min energy (winterfaces/get-max-energy this)))))
  
  (get-bandwidth [this]
    (get-in node-types [(:node-type this) :bandwidth]))
  
  (get-capacity [this]
    (get-in node-types [(:node-type this) :capacity]))
  
  (get-range [this]
    (get-in node-types [(:node-type this) :range]))
  
  (get-node-name [this]
    (deref-if-atom (:node-name this)))
  
  (get-password [this]
    (deref-if-atom (:password this))))

;; Also implement IWirelessTile (marker interface via metadata)
(alter-meta! #'->NodeTileEntity assoc :wireless-tile true)
(alter-meta! #'map->NodeTileEntity assoc :wireless-tile true)

;; ============================================================================
;; IInventory Protocol Implementation
;; ============================================================================

(extend-protocol inv/IInventory
  NodeTileEntity
  
  (get-size-inventory [this]
    2)  ; 2 slots: input and output
  
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
    64)
  
  (is-usable-by-player? [this player]
    true)
  
  (is-item-valid-for-slot? [this slot stack]
    ;; Only energy items are valid
    (energy/is-energy-item-supported? stack))
  
  (get-inventory-name [this]
    "wireless_node")
  
  (has-custom-name? [this]
    false))

(defn get-inventory-slot [tile slot-index]
  (get @(:inventory tile) slot-index))

(defn set-inventory-slot! [tile slot-index item-stack]
  (swap! (:inventory tile) assoc slot-index item-stack))

;; Energy percentage for display (0-4 levels)
(defn get-energy-percentage-level [tile]
  (let [energy (winterfaces/get-energy tile)
        max-energy (winterfaces/get-max-energy tile)
        pct (/ energy max-energy)]
    (min 4 (Math/round (* 4 pct)))))

;; Update logic - called every tick
(defn update-charge-in!
  "Update charging from input slot (slot 0) to node"
  [tile]
  (let [input-item (get-inventory-slot tile 0)]
    (if (and input-item (energy/is-energy-item-supported? input-item))
      (let [current-energy (winterfaces/get-energy tile)
            max-energy (winterfaces/get-max-energy tile)
            bandwidth (winterfaces/get-bandwidth tile)
            needed (min bandwidth (- max-energy current-energy))
            pulled (energy/pull-energy-from-item input-item needed false)]
        (when (> pulled 0)
          (winterfaces/set-energy tile (+ current-energy pulled))
          (reset! (:charging-in tile) true)
          (log/info "Node charging IN:" pulled "energy"))
        (when (<= pulled 0)
          (reset! (:charging-in tile) false)))
      (reset! (:charging-in tile) false))))

(defn update-charge-out!
  "Update charging from node to output slot (slot 1)"
  [tile]
  (let [output-item (get-inventory-slot tile 1)]
    (if (and output-item (energy/is-energy-item-supported? output-item))
      (let [current-energy (winterfaces/get-energy tile)]
        (when (> current-energy 0)
          (let [bandwidth (winterfaces/get-bandwidth tile)
                to-charge (min bandwidth current-energy)
                leftover (energy/charge-energy-to-item output-item to-charge false)
                charged (- to-charge leftover)]
            (when (> charged 0)
              (winterfaces/set-energy tile (- current-energy charged))
              (reset! (:charging-out tile) true)
              (log/info "Node charging OUT:" charged "energy"))
            (when (<= charged 0)
              (reset! (:charging-out tile) false)))))
      (reset! (:charging-out tile) false))))

(defn check-network-connection!
  "Check if node is connected to wireless network"
  [tile]
  (try
    (let [world (:world tile)
          pos (:pos tile)
          node-vblock (vb/create-vnode (.getX pos) (.getY pos) (.getZ pos))
          world-data (wd/get-world-data world)
          network (wd/get-network-by-node world-data node-vblock)
          connected? (and network (not @(:disposed network)))]
      (reset! (:enabled tile) connected?)
      connected?)
    (catch Exception e
      (log/debug "Failed to check network connection:" (.getMessage e))
      (reset! (:enabled tile) false)
      false)))

(defn sync-to-clients!
  "Synchronize node state to nearby clients"
  [tile]
  (try
    (let [world (:world tile)
          pos (:pos tile)
          sync-data {:pos-x (.getX pos)
                     :pos-y (.getY pos)
                     :pos-z (.getZ pos)
                     :energy (winterfaces/get-energy tile)
                     :max-energy (winterfaces/get-max-energy tile)
                     :enabled @(:enabled tile)
                     :node-name (winterfaces/get-node-name tile)
                     :node-type (:node-type tile)
                     :password (winterfaces/get-password tile)
                     :charging-in @(:charging-in tile)
                     :charging-out @(:charging-out tile)
                     :placer-name (:placer-name tile)}]
      ;; Use dynamic require to avoid circular dependencies
      (require 'my-mod.wireless.gui.node-sync)
      ((resolve 'my-mod.wireless.gui.node-sync/broadcast-node-state) 
       world pos sync-data))
    (catch Exception e
      (log/debug "Node sync not yet implemented:" (.getMessage e)))))

(defn update-node-tile!
  "Main update function - called every tick"
  [tile]
  (swap! (:update-ticker tile) inc)
  (let [tick @(:update-ticker tile)]
    ;; Update charging every tick
    (update-charge-in! tile)
    (update-charge-out! tile)
    
    ;; Check network connection every 20 ticks (1 second)
    (when (zero? (mod tick 20))
      (check-network-connection! tile)
      (sync-to-clients! tile)
      
      ;; Update block state for visual feedback
      (rebuild-block-state! tile))
    
    ;; Also update block state when energy changes significantly
    (when (zero? (mod tick 10))
      (rebuild-block-state! tile))))

;; ============================================================================
;; NBT Persistence (using NBT DSL)
;; ============================================================================

;; Define NBT serialization using declarative DSL
(nbt/defnbt node
  ;; Energy (uses protocol getter/setter)
  [:energy "energy" :double
   :getter winterfaces/get-energy
   :setter winterfaces/set-energy]
  
  ;; Node name (uses protocol getter and helper setter)
  [:node-name "nodeName" :string
   :getter winterfaces/get-node-name
   :setter set-node-name!]
  
  ;; Password (uses protocol getter and helper setter)
  [:password "password" :string
   :getter winterfaces/get-password
   :setter set-password-str!]
  
  ;; Placer name (direct field access)
  [:placer-name "placer" :string]
  
  ;; Inventory (uses inventory protocol)
  [:inventory "inventory" :inventory])

;; ============================================================================
;; ITickable Implementation
;; ============================================================================

(deftype NodeTileEntityTickable [^:volatile-mutable tile-data]
  ;; Note: In real implementation, this would implement:
  ;; platform tick interface
  ;; platform tile-entity base class
  
  ;; ITickable interface
  ;; update() method - called every game tick
  Object
  (toString [this]
    (str "NodeTileEntityTickable[" (:node-type tile-data) "]@" (:pos tile-data)))
  
  clojure.lang.IDeref
  (deref [this] tile-data)
  
  clojure.lang.IFn
  (invoke [this]
    ;; This acts as the update() method
    (when tile-data
      (try
        (update-node-tile! tile-data)
        (catch Exception e
          (log/error "Error updating node tile:" (.getMessage e))))))
  (invoke [this arg]
    ;; Allow getting/setting tile data
    (cond
      (= arg :get-tile-data) tile-data
      (= arg :update) (do (update-node-tile! tile-data) nil)
      :else nil)))

(defn create-tickable-node-tile-entity
  "Create a tickable node tile entity that implements ITickable
  
  Parameters:
  - node-type: :basic, :standard, or :advanced
  - world: World instance
  - pos: BlockPos instance
  
  Returns: NodeTileEntityTickable instance"
  [node-type world pos]
  (let [tile-data (create-node-tile-entity node-type world pos)]
    (NodeTileEntityTickable. tile-data)))

(defn get-tile-data
  "Extract NodeTileEntity data from tickable wrapper
  
  Parameters:
  - tickable-tile: NodeTileEntityTickable instance
  
  Returns: NodeTileEntity record"
  [tickable-tile]
  (if (instance? NodeTileEntityTickable tickable-tile)
    @tickable-tile
    tickable-tile))

(defn tick-tile!
  "Manually tick a tile entity (calls update)
  
  Parameters:
  - tickable-tile: NodeTileEntityTickable instance"
  [tickable-tile]
  (when (instance? NodeTileEntityTickable tickable-tile)
    (tickable-tile)))

;; Compatibility: Allow both tickable and non-tickable tiles
(defn as-tile-data
  "Convert any tile to NodeTileEntity data
  
  Parameters:
  - tile: NodeTileEntity or NodeTileEntityTickable
  
  Returns: NodeTileEntity record"
  [tile]
  (if (instance? NodeTileEntityTickable tile)
    @tile
    tile))

;; Node management functions
(defn set-placer! [tile player-name]
  (assoc tile :placer-name player-name))

;; Serialize/deserialize for NBT
(defn node-to-nbt
  "Convert node tile entity to NBT-like map"
  [tile]
  {:energy (winterfaces/get-energy tile)
  :node-name (winterfaces/get-node-name tile)
  :password (winterfaces/get-password tile)
   :placer-name (:placer-name tile)
   :node-type (:node-type tile)})

(defn node-from-nbt
  "Restore node tile entity from NBT-like map"
  [tile nbt-data]
  (winterfaces/set-energy tile (:energy nbt-data))
  (set-node-name! tile (:node-name nbt-data))
  (set-password-str! tile (:password nbt-data))
  (-> tile
      (assoc :placer-name (:placer-name nbt-data))
      (assoc :node-type (:node-type nbt-data))))

;; ============================================================================
;; TileEntity Registry
;; ============================================================================

;; Global registry for tile entities (in real impl, this would be per-world)
;; Supports both NodeTileEntity and NodeTileEntityTickable
(defonce node-tiles (atom {}))

(defn register-node-tile! [pos tile]
  "Register a tile entity at a position
  
  Parameters:
  - pos: BlockPos
  - tile: NodeTileEntity or NodeTileEntityTickable"
  (swap! node-tiles assoc pos tile))

(defn unregister-node-tile! [pos]
  (swap! node-tiles dissoc pos))

(defn get-node-tile [pos]
  "Get tile entity at position, unwrapping if tickable
  
  Parameters:
  - pos: BlockPos
  
  Returns: NodeTileEntity record (unwrapped if necessary)"
  (let [tile (get @node-tiles pos)]
    (as-tile-data tile)))

;; Block interaction handlers
(defn handle-node-right-click [node-type]
  (fn [event-data]
    (log/info "Wireless Node (" (name node-type) ") right-clicked!")
    (let [{:keys [player world pos]} event-data
          tile (get-node-tile pos)]
      (if tile
        (do
          (log/info "Node status:")
          (log/info "  Energy:" (winterfaces/get-energy tile) "/" (winterfaces/get-max-energy tile))
          (log/info "  Connected:" @(:enabled tile))
          (log/info "  Charging In:" @(:charging-in tile))
          (log/info "  Charging Out:" @(:charging-out tile))
          (log/info "  Name:" (winterfaces/get-node-name tile))
          ;; Open GUI
          (try
            (if-let [open-node-gui (requiring-resolve 'my-mod.wireless.gui.registry/open-node-gui)]
              (do
                (open-node-gui player world pos)
                (log/info "Opened Node GUI"))
              (log/error "Node GUI registry function not found"))
            (catch Exception e
              (log/error "Failed to open Node GUI:" (.getMessage e)))))
        (log/info "No tile entity found!")))))

(defn handle-node-place [node-type]
  (fn [event-data]
    (log/info "Placing Wireless Node (" (name node-type) ")")
    (let [{:keys [player world pos]} event-data
          player-name (str player)
          ;; Create tickable tile entity
          tickable-tile (create-tickable-node-tile-entity node-type world pos)
          tile-data (get-tile-data tickable-tile)]
      ;; Set placer
      (set-placer! tile-data player-name)
      ;; Register tickable tile entity
      (register-node-tile! pos tickable-tile)
      (log/info "Node placed by" player-name "at" pos)
      (log/info "Tickable TileEntity registered for automatic updates"))))

(defn handle-node-break [node-type]
  (fn [event-data]
    (log/info "Breaking Wireless Node (" (name node-type) ")")
    (let [{:keys [world pos]} event-data
          tile (get-node-tile pos)]
      (when tile
        ;; Drop items from inventory
        (doseq [item @(:inventory tile)]
          (when item
            (log/info "Dropping item:" item)))
        ;; Unregister tile entity
        (unregister-node-tile! pos)))))

;; Define the three node blocks
(bdsl/defblock wireless-node-basic
  :registry-name "node_basic"
  :material :metal
  :hardness 2.5
  :resistance 6.0
  :requires-tool true
  :harvest-tool :pickaxe
  :harvest-level 1
  :sounds :metal
  :on-right-click (handle-node-right-click :basic)
  :on-place (handle-node-place :basic)
  :on-break (handle-node-break :basic))

(bdsl/defblock wireless-node-standard
  :registry-name "node_standard"
  :material :metal
  :hardness 2.5
  :resistance 6.0
  :requires-tool true
  :harvest-tool :pickaxe
  :harvest-level 1
  :sounds :metal
  :on-right-click (handle-node-right-click :standard)
  :on-place (handle-node-place :standard)
  :on-break (handle-node-break :standard))

(bdsl/defblock wireless-node-advanced
  :registry-name "node_advanced"
  :material :metal
  :hardness 2.5
  :resistance 6.0
  :requires-tool true
  :harvest-tool :pickaxe
  :harvest-level 1
  :sounds :metal
  :on-right-click (handle-node-right-click :advanced)
  :on-place (handle-node-place :advanced)
  :on-break (handle-node-break :advanced))

;; Helper: Get all wireless node blocks
(defn get-all-wireless-nodes []
  [wireless-node-basic
   wireless-node-standard
   wireless-node-advanced])

;; Initialize wireless nodes
(defn init-wireless-nodes! []
  (log/info "Initialized Wireless Nodes:")
  (log/info "  - Basic: max-energy=" (:max-energy (:basic node-types)))
  (log/info "  - Standard: max-energy=" (:max-energy (:standard node-types)))
  (log/info "  - Advanced: max-energy=" (:max-energy (:advanced node-types))))

;; ============================================================================
;; Tick System
;; ============================================================================

;; Tick handler - should be called every game tick for all active nodes
;; Note: With ITickable implementation, Minecraft calls update() automatically
;; This function is for manual/fallback ticking if needed
(defn tick-all-nodes! []
  "Manually tick all registered nodes
  
  Note: With ITickable implementation, this is only needed for fallback/testing.
  Minecraft's TileEntity system will automatically call update() on ITickable tiles."
  (doseq [[pos tile] @node-tiles]
    (if (instance? NodeTileEntityTickable tile)
      ;; Tickable tile - call its update method
      (tick-tile! tile)
      ;; Non-tickable tile - call update directly
      (update-node-tile! tile))))

(defn get-active-node-count []
  "Get count of active nodes in registry"
  (count @node-tiles))

(defn get-all-node-positions []
  "Get all positions with registered nodes"
  (keys @node-tiles))

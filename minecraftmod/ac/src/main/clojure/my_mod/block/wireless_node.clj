(ns my-mod.block.wireless-node
  "Wireless Node block implementation - energy network node with item charging.

  State model (Design-3):
  All persistent state lives in ScriptedBlockEntity.customState as a Clojure
  persistent map. The node-tiles atom has been removed.

  State map shape:
    {:node-type    keyword  ; :basic :standard :advanced
     :node-name    String
     :password     String
     :placer-name  String
     :energy       double
     :enabled      boolean
     :charging-in  boolean
     :charging-out boolean
     :update-ticker int
     :inventory    [ItemStack|nil ItemStack|nil]}"
  (:require [my-mod.block.dsl :as bdsl]
            [my-mod.block.tile-dsl :as tdsl]
            [my-mod.block.tile-logic :as tile-logic]
            [my-mod.block.role-impls :as impls]
            [my-mod.platform.capability :as platform-cap]
            [my-mod.platform.world :as world]
            [clojure.string :as str]
            [my-mod.energy.operations :as energy]
            [my-mod.wireless.interfaces :as winterfaces]
            [my-mod.inventory.core :as inv]
            [my-mod.nbt.dsl :as nbt]
            [my-mod.wireless.world-data :as wd]
            [my-mod.wireless.virtual-blocks :as vb]
            [my-mod.util.log :as log])
  (:import [my_mod.api.wireless IWirelessNode]
           [my_mod.api.energy IEnergyCapable]))

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
              (let [state-def (.getStateDefinition block)
                    energy-prop (when state-def (.getProperty state-def "energy"))
                    connected-prop (when state-def (.getProperty state-def "connected"))
                    new-state (-> current-state
                                (as-> state
                                  (if energy-prop
                                    (.setValue state energy-prop (Integer/valueOf energy-level))
                                    state))
                                (as-> state
                                  (if connected-prop
                                    (.setValue state connected-prop (Boolean/valueOf connected))
                                    state))) ]
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
            block (.getBlock base-state)
            state-def (.getStateDefinition block)
            energy-prop (when state-def (.getProperty state-def "energy"))
            connected-prop (when state-def (.getProperty state-def "connected"))]
          
          (log/debug "Getting actual state for" pos
                    "energy:" energy-level
                    "connected:" connected)
          
          ;; Try to update block state with properties
          (try
            (-> base-state
              ;; Try to set ENERGY property
              (as-> state
                (if energy-prop
                  (.setValue state energy-prop (Integer/valueOf energy-level))
                  state))
              ;; Try to set CONNECTED property
              (as-> state
                (if connected-prop
                  (.setValue state connected-prop (Boolean/valueOf connected))
                  state)))
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
;; Default state
;; ============================================================================

(def node-default-state
  {:node-type     :basic
   :node-name     "Unnamed"
   :password      ""
   :placer-name   ""
   :energy        0.0
   :enabled       false
   :charging-in   false
   :charging-out  false
   :update-ticker 0
   :inventory     [nil nil]})

(defn- parse-node-type [block-id-or-kw]
  (let [s (if (keyword? block-id-or-kw) (name block-id-or-kw) (str block-id-or-kw))]
    (cond
      (str/includes? s "advanced") :advanced
      (str/includes? s "standard") :standard
      :else :basic)))

(defn- node-safe-state [be block-id]
  (or (.getCustomState be)
      (assoc node-default-state :node-type (parse-node-type block-id))))

(defn- node-max-energy [state]
  (get-in node-types [(keyword (:node-type state :basic)) :max-energy] 15000))

;; ============================================================================
;; Design-3 tick logic (functional, operates on state map)
;; ============================================================================

(defn- tick-charge-in
  "Attempt to pull energy from inventory[0] into the node. Returns updated state."
  [state]
  (let [input-item (get-in state [:inventory 0])]
    (if (and input-item (energy/is-energy-item-supported? input-item))
      (let [cur       (double (:energy state 0.0))
            max-e     (double (node-max-energy state))
            bandwidth (double (get-in node-types [(keyword (:node-type state :basic)) :bandwidth] 150))
            needed    (min bandwidth (- max-e cur))
            pulled    (energy/pull-energy-from-item input-item needed false)]
        (if (pos? pulled)
          (assoc state :energy (+ cur pulled) :charging-in true)
          (assoc state :charging-in false)))
      (assoc state :charging-in false))))

(defn- tick-charge-out
  "Attempt to push energy from node to inventory[1]. Returns updated state."
  [state]
  (let [output-item (get-in state [:inventory 1])
        cur         (double (:energy state 0.0))]
    (if (and output-item (energy/is-energy-item-supported? output-item) (pos? cur))
      (let [bandwidth (double (get-in node-types [(keyword (:node-type state :basic)) :bandwidth] 150))
            to-charge (min bandwidth cur)
            leftover  (energy/charge-energy-to-item output-item to-charge false)
            charged   (- to-charge leftover)]
        (if (pos? charged)
          (assoc state :energy (- cur charged) :charging-out true)
          (assoc state :charging-out false)))
      (assoc state :charging-out false))))

(defn- tick-check-network
  "Update :enabled flag based on world-data network lookup. Returns updated state."
  [state level pos]
  (try
    (let [vblock      (vb/create-vnode (.getX pos) (.getY pos) (.getZ pos))
          world-data  (wd/get-world-data level)
          network     (wd/get-network-by-node world-data vblock)
          connected?  (and network (not (:disposed network)))]
      (assoc state :enabled connected?))
    (catch Exception _
      (assoc state :enabled false))))

;; ============================================================================
;; Scripted BE adapter functions (Design-3)
;; ============================================================================

(defn node-scripted-tick-fn
  [level pos _block-state be]
  (let [block-id (.getBlockId be)
        state    (node-safe-state be block-id)
        ticker   (inc (get state :update-ticker 0))
        state    (assoc state :update-ticker ticker)
        ;; Every tick: charge in/out
        state    (try (tick-charge-in state)  (catch Exception _ state))
        state    (try (tick-charge-out state) (catch Exception _ state))
        ;; Every 20 ticks: check network + sync
        state    (if (zero? (mod ticker 20))
                   (let [state (try (tick-check-network state level pos) (catch Exception _ state))]
                     ;; Sync block state (energy level / connected)
                     (try
                       (let [blk-state (world/world-get-block-state level pos)]
                         (when blk-state
                           (let [energy-pct (/ (:energy state 0.0) (max 1 (node-max-energy state)))
                                 e-level    (min 4 (int (Math/round (* 4 (double energy-pct)))))]
                             (when-let [block (.getBlock blk-state)]
                               (let [state-def (.getStateDefinition block)
                                     ep        (when state-def (.getProperty state-def "energy"))
                                     cp        (when state-def (.getProperty state-def "connected"))
                                     new-bs    (cond-> blk-state
                                                 ep (.setValue ep (Integer/valueOf e-level))
                                                 cp (.setValue cp (Boolean/valueOf (:enabled state false))))]
                                 (when (not= new-bs blk-state)
                                   (.setBlock level pos new-bs 3)))))))
                       (catch Exception _))
                     ;; Broadcast sync
                     (try
                       (require 'my-mod.wireless.gui.node-sync)
                       ((resolve 'my-mod.wireless.gui.node-sync/broadcast-node-state)
                        level pos
                        {:pos-x (.getX pos) :pos-y (.getY pos) :pos-z (.getZ pos)
                         :energy (:energy state)
                         :max-energy (node-max-energy state)
                         :enabled (:enabled state)
                         :node-name (:node-name state)
                         :node-type (:node-type state)
                         :password (:password state)})
                       (catch Exception _))
                     state)
                   state)]
    (.setCustomState be state)
    (.setChanged be)))

(defn node-scripted-load-fn
  "Deserialize CompoundTag → state map."
  [tag]
  (let [state (assoc node-default-state
                :energy     (if (.contains tag "Energy")   (.getDouble  tag "Energy")   0.0)
                :node-type  (keyword (if (.contains tag "NodeType")  (.getString  tag "NodeType")  "basic"))
                :node-name  (if (.contains tag "NodeName") (.getString  tag "NodeName") "Unnamed")
                :password   (if (.contains tag "Password") (.getString  tag "Password") "")
                :enabled    (if (.contains tag "Enabled")  (.getBoolean tag "Enabled")  false)
                :placer-name (if (.contains tag "Placer")  (.getString  tag "Placer")   ""))]
    ;; Deserialize inventory
    (if (.contains tag "NodeInventory")
      (let [inv-tag (.getList tag "NodeInventory" 10)
            inv     (reduce (fn [v i]
                              (let [st   (.getCompound inv-tag i)
                                    slot (.getInt st "Slot")
                                    item (net.minecraft.world.item.ItemStack/of st)]
                                (if (and (>= slot 0) (< slot 2))
                                  (assoc v slot (when-not (.isEmpty item) item))
                                  v)))
                            [nil nil]
                            (range (.size inv-tag)))]
        (assoc state :inventory inv))
      state)))

(defn node-scripted-save-fn
  "Serialize state map from BE customState → CompoundTag."
  [be tag]
  (let [state (or (.getCustomState be) node-default-state)]
    (.putDouble  tag "Energy"    (double (:energy state 0.0)))
    (.putString  tag "NodeType"  (name   (:node-type state :basic)))
    (.putString  tag "NodeName"  (str    (:node-name state "Unnamed")))
    (.putString  tag "Password"  (str    (:password state "")))
    (.putBoolean tag "Enabled"   (boolean (:enabled state false)))
    (.putString  tag "Placer"    (str    (:placer-name state "")))
    ;; Serialize inventory
    (let [inv      (:inventory state [nil nil])
          inv-list (net.minecraft.nbt.ListTag.)]
      (doseq [slot (range 2)]
        (when-let [item (nth inv slot nil)]
          (let [st (net.minecraft.nbt.CompoundTag.)]
            (.putInt st "Slot" slot)
            (.save item st)
            (.add inv-list st))))
      (.put tag "NodeInventory" inv-list))))

;; ============================================================================
;; Container functions (slot access via BE customState)
;; ============================================================================

(def ^:private node-container-fns
  {:get-size (fn [_be] 2)

   :get-item (fn [be slot]
               (get-in (or (.getCustomState be) node-default-state) [:inventory slot]))

   :set-item! (fn [be slot item]
                (let [state  (or (.getCustomState be) node-default-state)
                      state' (assoc-in state [:inventory slot] item)]
                  (.setCustomState be state')))

   :remove-item (fn [be slot amount]
                  (let [state (or (.getCustomState be) node-default-state)
                        item  (get-in state [:inventory slot])]
                    (when item
                      (let [cnt (.getCount item)]
                        (if (<= cnt amount)
                          (do (.setCustomState be (assoc-in state [:inventory slot] nil)) item)
                          (.splitStack item amount))))))

   :remove-item-no-update (fn [be slot]
                            (let [state (or (.getCustomState be) node-default-state)
                                  item  (get-in state [:inventory slot])]
                              (.setCustomState be (assoc-in state [:inventory slot] nil))
                              item))

   :clear! (fn [be]
             (.setCustomState be (assoc (or (.getCustomState be) node-default-state)
                                        :inventory [nil nil])))

   :still-valid? (fn [_be _player] true)

   :slots-for-face (fn [_be _face] (int-array [0 1]))

   :can-place-through-face? (fn [_be slot item _face]
                               (cond
                                 (= slot 0) (energy/is-energy-item-supported? item)
                                 :else false))

   :can-take-through-face? (fn [_be slot _item _face] (= slot 1))})

;; ============================================================================
;; Tile DSL (shared BlockEntityType across node tiers)
;; ============================================================================

(tdsl/deftile-kind :wireless-node
  :tick-fn node-scripted-tick-fn
  :read-nbt-fn node-scripted-load-fn
  :write-nbt-fn node-scripted-save-fn)

(tdsl/deftile wireless-node-basic-tile
  :id "wireless-node-basic"
  :registry-name "node_basic"
  :impl :scripted
  :blocks ["wireless-node-basic"]
  :tile-kind :wireless-node)

(tdsl/deftile wireless-node-standard-tile
  :id "wireless-node-standard"
  :registry-name "node_standard"
  :impl :scripted
  :blocks ["wireless-node-standard"]
  :tile-kind :wireless-node)

(tdsl/deftile wireless-node-advanced-tile
  :id "wireless-node-advanced"
  :registry-name "node_advanced"
  :impl :scripted
  :blocks ["wireless-node-advanced"]
  :tile-kind :wireless-node)

;; Register Capabilities (once per capability type - idempotent)
(platform-cap/declare-capability! :wireless-node IWirelessNode
  (fn [be _side] (impls/->WirelessNodeImpl be)))

(platform-cap/declare-capability! :wireless-energy IEnergyCapable
  (fn [be _side] (impls/->ClojureEnergyImpl be)))

;; Register for each node tier
(doseq [tile-id ["wireless-node-basic" "wireless-node-standard" "wireless-node-advanced"]]
  (tile-logic/register-tile-capability! tile-id :wireless-node)
  (tile-logic/register-tile-capability! tile-id :wireless-energy)
  (tile-logic/register-container! tile-id node-container-fns))

;; Block interaction handlers
(defn handle-node-right-click [node-type]
  (fn [event-data]
    (log/info "Wireless Node (" (name node-type) ") right-clicked!")
    (let [{:keys [player world pos]} event-data
          be    (world/world-get-tile-entity world pos)
          state (when be (or (.getCustomState be) node-default-state))]
      (if state
        (do
          (log/info "Node status:")
          (log/info "  Energy:" (:energy state) "/" (node-max-energy state))
          (log/info "  Connected:" (:enabled state))
          (log/info "  Name:" (:node-name state))
          (try
            (if-let [open-node-gui (requiring-resolve 'my-mod.wireless.gui.registry/open-node-gui)]
              (let [result (open-node-gui player world pos)]
                (log/info "Opened Node GUI")
                result)
              (do (log/error "Node GUI registry function not found") nil))
            (catch Exception e
              (log/error "Failed to open Node GUI:" (.getMessage e))
              nil)))
        (log/info "No tile entity found!")))))

(defn handle-node-place [node-type]
  (fn [event-data]
    (log/info "Placing Wireless Node (" (name node-type) ")")
    (let [{:keys [player world pos]} event-data
          player-name (str player)
          be          (world/world-get-tile-entity world pos)]
      (when be
        (let [state (or (.getCustomState be) node-default-state)]
          (.setCustomState be (assoc state
                                :node-type   node-type
                                :placer-name player-name))))
      (log/info "Node placed by" player-name "at" pos))))

(defn handle-node-break [node-type]
  (fn [event-data]
    (log/info "Breaking Wireless Node (" (name node-type) ")")
    (let [{:keys [world pos]} event-data
          be (world/world-get-tile-entity world pos)]
      (when be
        (let [state (or (.getCustomState be) node-default-state)]
          ;; Drop inventory items
          (doseq [item (:inventory state [])]
            (when item (log/info "Dropping item:" item))))))))

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
  :model-parent "minecraft:block/cube_all"
  :block-state-properties block-state-properties  ;; Dynamic properties: energy (0-4), connected (boolean)
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
  :model-parent "minecraft:block/cube_all"
  :block-state-properties block-state-properties  ;; Dynamic properties: energy (0-4), connected (boolean)
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
  :model-parent "minecraft:block/cube_all"
  :block-state-properties block-state-properties  ;; Dynamic properties: energy (0-4), connected (boolean)
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
  (log/info "Initialized Wireless Nodes (Design-3: customState):")
  (log/info "  - Basic: max-energy=" (:max-energy (:basic node-types)))
  (log/info "  - Standard: max-energy=" (:max-energy (:standard node-types)))
  (log/info "  - Advanced: max-energy=" (:max-energy (:advanced node-types)))
  (log/info "  - Capabilities :wireless-node + :wireless-energy registered"))

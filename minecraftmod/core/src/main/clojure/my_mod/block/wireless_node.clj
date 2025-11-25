(ns my-mod.block.wireless-node
  "Wireless Node block implementation - energy network node with item charging"
  (:require [my-mod.block.dsl :as bdsl]
            [my-mod.energy.stub :as energy]
            [my-mod.wireless.interfaces :as winterfaces]
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

;; TileEntity state - using atom to hold mutable state
(defrecord NodeTileEntity 
  [;; Identity
   node-type          ; :basic, :standard, or :advanced
   node-name          ; String - node name
   password           ; String - network password
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
    "Unnamed"
    ""
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
  (assoc tile :node-name name))

(defn set-password-str! [tile password]
  (assoc tile :password password))

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
    (:node-name this))
  
  (get-password [this]
    (:password this)))

;; Also implement IWirelessTile (marker interface via metadata)
(alter-meta! #'->NodeTileEntity assoc :wireless-tile true)
(alter-meta! #'map->NodeTileEntity assoc :wireless-tile true)

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
  ;; TODO: Integrate with WirelessNet system
  (let [connected? false] ; Placeholder
    (reset! (:enabled tile) connected?)
    connected?))

(defn sync-to-clients!
  "Synchronize node state to nearby clients"
  [tile]
  ;; TODO: Implement network sync
  (let [sync-data {:enabled @(:enabled tile)
                   :charging-in @(:charging-in tile)
                   :charging-out @(:charging-out tile)
                   :energy (winterfaces/get-energy tile)
                   :node-name (winterfaces/get-node-name tile)
                   :password (winterfaces/get-password tile)
                   :placer-name (:placer-name tile)}]
    (log/debug "Sync node data:" sync-data)))

(defn update-node-tile!
  "Main update function - called every tick"
  [tile]
  ;; Increment ticker
  (swap! (:update-ticker tile) inc)
  
  ;; Every 10 ticks: check network and sync
  (when (>= @(:update-ticker tile) 10)
    (reset! (:update-ticker tile) 0)
    (check-network-connection! tile)
    (sync-to-clients! tile))
  
  ;; Every tick: update charging
  (update-charge-in! tile)
  (update-charge-out! tile))

;; Node management functions
(defn set-node-password! [tile password]
  (assoc tile :password password))

(defn set-node-name! [tile name]
  (assoc tile :node-name name))

(defn set-placer! [tile player-name]
  (assoc tile :placer-name player-name))

;; Serialize/deserialize for NBT
(defn node-to-nbt
  "Convert node tile entity to NBT-like map"
  [tile]
  {:energy (get-energy tile)
   :node-name (:node-name tile)
   :password (:password tile)
   :placer-name (:placer-name tile)
   :node-type (:node-type tile)})

(defn node-from-nbt
  "Restore node tile entity from NBT-like map"
  [tile nbt-data]
  (set-energy! tile (:energy nbt-data))
  (-> tile
      (assoc :node-name (:node-name nbt-data))
      (assoc :password (:password nbt-data))
      (assoc :placer-name (:placer-name nbt-data))
      (assoc :node-type (:node-type nbt-data))))

;; Global registry for tile entities (in real impl, this would be per-world)
(defonce node-tiles (atom {}))

(defn register-node-tile! [pos tile]
  (swap! node-tiles assoc pos tile))

(defn unregister-node-tile! [pos]
  (swap! node-tiles dissoc pos))

(defn get-node-tile [pos]
  (get @node-tiles pos))

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
          ;; TODO: Open GUI
          )
        (log/info "No tile entity found!")))))

(defn handle-node-place [node-type]
  (fn [event-data]
    (log/info "Placing Wireless Node (" (name node-type) ")")
    (let [{:keys [player world pos]} event-data
          player-name (str player)
          tile (create-node-tile-entity node-type world pos)]
      ;; Set placer
      (set-placer! tile player-name)
      ;; Register tile entity
      (register-node-tile! pos tile)
      (log/info "Node placed by" player-name "at" pos))))

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

;; Tick handler - should be called every game tick for all active nodes
(defn tick-all-nodes! []
  (doseq [[pos tile] @node-tiles]
    (update-node-tile! tile)))

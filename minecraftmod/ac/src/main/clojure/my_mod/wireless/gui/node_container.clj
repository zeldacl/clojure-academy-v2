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

(defn- resolve-state
  "Resolve [be state] from either a ScriptedBlockEntity or a legacy map."
  [tile]
  (if (map? tile)
    [nil tile]
    (try
      [tile (or (.getCustomState tile) {})]
      (catch Exception e
        (log/warn "Could not resolve customState from BE:" (.getMessage e))
        [tile {}]))))

(defn create-container
  "Create a Node GUI container instance.

  Args:
  - tile: ScriptedBlockEntity or legacy Clojure state map
  - player: Player who opened GUI

  Returns: NodeContainer record"
  [tile player]
  (let [[be state] (resolve-state tile)
        entity     (or be tile)]
    (->NodeContainer
      entity
      player
      (atom (int (get state :energy 0.0)))
      (atom (int (get state :max-energy 15000)))
      (atom (keyword (get state :node-type :basic)))
      (atom (boolean (get state :enabled false)))
      (atom (str (get state :node-name "Unnamed")))
      (atom (str (get state :password "")))
      (atom 0)      ; transfer-rate
      (atom 0)      ; capacity
      (atom 0)      ; max-capacity
      (atom 0)      ; charge-ticker
      (atom 0))))

;; ============================================================================
;; Slot Management
;; ============================================================================

(def slot-input 0)
(def slot-output 1)

(defn- tile-state
  "Get current state map from tile-entity (BE or legacy map)."
  [tile]
  (if (map? tile)
    tile
    (try (.getCustomState tile) (catch Exception _ {}))))

(defn get-slot-count
  "Get total slot count (2 for node)"
  [_container]
  2)

(defn get-owner
  "Get node owner name"
  [container]
  (let [tile (:tile-entity container)]
    (:placer-name (tile-state tile))))

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
  "Get item from slot. Reads from BE customState when tile-entity is a BE."
  [container slot-index]
  (let [tile (:tile-entity container)]
    (if (map? tile)
      (common/get-slot-item container slot-index)
      (try
        (get-in (.getCustomState tile) [:inventory slot-index])
        (catch Exception _ (common/get-slot-item container slot-index))))))

(defn set-slot-item!
  "Set item in slot. Writes to BE customState when tile-entity is a BE."
  [container slot-index item-stack]
  (let [tile (:tile-entity container)]
    (if (map? tile)
      (common/set-slot-item! container slot-index item-stack)
      (try
        (let [state  (or (.getCustomState tile) {})
              state' (assoc-in state [:inventory slot-index] item-stack)]
          (.setCustomState tile state'))
        (catch Exception _
          (common/set-slot-item! container slot-index item-stack))))))

(defn slot-changed!
  "Called when slot contents change"
  [container slot-index]
  (log/info "Node container slot" slot-index "changed"))

;; ============================================================================
;; Data Synchronization
;; ============================================================================

(defn sync-to-client!
  "Update container data from tile entity (server -> client).
  Works with both ScriptedBlockEntity (Design-3) and legacy Clojure maps."
  [container]
  (let [tile  (:tile-entity container)
        state (or (tile-state tile) {})]
    ;; Update energy (every tick)
    (reset! (:energy container)     (int (get state :energy 0.0)))
    (reset! (:max-energy container) (int (get state :max-energy 15000)))

    ;; Update connection status
    (reset! (:is-online container) (boolean (get state :enabled false)))

    ;; Update node info
    (reset! (:ssid container)     (str (get state :node-name "Unnamed")))
    (reset! (:password container) (str (get state :password "")))

    ;; Update network capacity (throttled)
    (sync-helpers/with-throttled-sync! (:sync-ticker container) 100
      (fn [] (sync-helpers/query-node-network-capacity! container)))

    ;; Compute transfer rate
    (let [rate (cond
                 (and (get state :charging-in false) (get state :charging-out false)) 200
                 (get state :charging-in false)  100
                 (get state :charging-out false) 100
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
  "Called every tick on server side.
  Updates synced data. Actual charging logic runs in the scripted BE tick fn."
  [container]
  (sync-to-client! container)
  (swap! (:charge-ticker container) inc))

;; ============================================================================
;; Button Actions
;; ============================================================================

(def button-toggle-connection 0)
(def button-set-ssid 1)
(def button-set-password 2)

(defn handle-button-click!
  "Handle button click from client.
  Updates BE customState for Design-3 BEs; ignores for legacy maps."
  [container button-id data]
  (let [tile (:tile-entity container)]
    (when-not (map? tile)
      (case button-id
        0 ; Toggle connection
        (let [state  (or (.getCustomState tile) {})
              state' (update state :enabled not)]
          (.setCustomState tile state')
          (log/info "Toggled node connection:" (:enabled state')))

        1 ; Set SSID
        (when-let [new-ssid (:ssid data)]
          (let [state  (or (.getCustomState tile) {})
                state' (assoc state :node-name new-ssid)]
            (.setCustomState tile state')
            (log/info "Set node SSID to:" new-ssid)))

        2 ; Set password
        (when-let [new-password (:password data)]
          (let [state  (or (.getCustomState tile) {})
                state' (assoc state :password new-password)]
            (.setCustomState tile state')
            (log/info "Set node password")))

        (log/warn "Unknown button ID:" button-id)))))

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

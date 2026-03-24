(ns cn.li.ac.block.wireless-node.block
  "Wireless Node block - energy network node with item charging.

  This file contains:
  - Block state schema (NBT fields)
  - Block definitions (basic/standard/advanced tiers)
  - Server-side logic (tick, NBT, container)
  - Network message handlers (server-side)

  Architecture:
  All persistent state lives in ScriptedBlockEntity.customState as a Clojure
  persistent map. The schema defines all fields and their serialization."
  (:require [cn.li.mcmod.block.dsl           :as bdsl]
            [cn.li.mcmod.block.tile-dsl      :as tdsl]
            [cn.li.mcmod.block.tile-logic    :as tile-logic]
            [cn.li.mcmod.block.state-schema  :as schema]
            [cn.li.mcmod.gui.slot-schema     :as slot-schema]
            [cn.li.mcmod.platform.capability :as platform-cap]
            [cn.li.mcmod.platform.world      :as world]
            [cn.li.mcmod.platform.be         :as platform-be]
            [cn.li.mcmod.platform.item       :as pitem]
            [cn.li.mcmod.platform.position   :as pos]
            [cn.li.mcmod.platform.nbt        :as nbt]
            [cn.li.mcmod.network.server      :as net-server]
            [clojure.string                  :as str]
            [cn.li.ac.energy.operations      :as energy]
            [cn.li.ac.wireless.world-data    :as wd]
            [cn.li.ac.wireless.world-data    :as world-data]
            [cn.li.ac.wireless.virtual-blocks :as vb]
            [cn.li.ac.wireless.gui.message-registry :as msg-registry]
            [cn.li.ac.wireless.helper        :as helper]
            [cn.li.ac.wireless.network       :as wireless-net]
            [cn.li.ac.wireless.interfaces    :as winterfaces]
            [cn.li.ac.wireless.gui.network-handler-helpers :as net-helpers]
            [cn.li.mcmod.util.log            :as log])
  (:import [cn.li.acapi.wireless IWirelessNode]
           [cn.li.acapi.energy IEnergyCapable]))

;; ============================================================================
;; Message ID Helper
;; ============================================================================

(msg-registry/register-block-messages!
  :node
  [:get-status :change-name :change-password :list-networks :connect :disconnect])

(defn- msg
  "Generate message ID for node actions."
  [action]
  (msg-registry/msg :node action))

;; ============================================================================
;; Part 1: Node Type Specifications and State Schema
;; ============================================================================

(def node-types
  "Single source of truth for per-tier capability values."
  {:basic    {:max-energy  15000 :bandwidth 150 :range  9 :capacity  5}
   :standard {:max-energy  50000 :bandwidth 300 :range 12 :capacity 10}
   :advanced {:max-energy 200000 :bandwidth 900 :range 19 :capacity 20}})

(defn node-max-energy
  "Derive the max-energy for a state map from its :node-type."
  [state]
  (get-in node-types [(keyword (:node-type state :basic)) :max-energy] 15000))

(defn- load-inventory
  "Deserialise a ListTag of ItemStack compounds into a [slot0 slot1] vector."
  [tag nbt-key default]
  (if (nbt/nbt-has-key? tag nbt-key)
    (let [inv-tag (nbt/nbt-get-list tag nbt-key)
          size    (nbt/nbt-list-size inv-tag)]
      (reduce
        (fn [v i]
          (let [st   (nbt/nbt-list-get-compound inv-tag i)
                slot (nbt/nbt-get-int st "Slot")
                item (pitem/create-item-from-nbt st)]
            (if (and (>= slot 0) (< slot (count v)))
              (assoc v slot (when-not (pitem/item-is-empty? item) item))
              v)))
        default
        (range size)))
    default))

(defn- save-inventory
  "Serialise a [slot0 slot1] vector into a ListTag and attach it to tag."
  [state tag nbt-key]
  (let [inv      (get state :inventory [nil nil])
        inv-list (nbt/create-nbt-list)]
    (doseq [slot (range (count inv))]
      (when-let [item (nth inv slot nil)]
        (let [st (nbt/create-nbt-compound)]
          (nbt/nbt-set-int! st "Slot" slot)
          (pitem/item-save-to-nbt item st)
          (nbt/nbt-append! inv-list st))))
    (nbt/nbt-set-tag! tag nbt-key inv-list)))

(def node-state-schema
  "Single source of truth for all node state fields.

  Adding/removing/renaming a field only requires editing this vector.
  NBT serialization, GUI sync, and default state are derived automatically."
  [;; Identity
   {:key :node-type     :nbt-key "NodeType"  :type :keyword  :default :basic
    :persist? true  :gui-sync? true}

   {:key :node-name     :nbt-key "NodeName"  :type :string   :default "Unnamed"
    :persist? true  :gui-sync? true}

   {:key :password      :nbt-key "Password"  :type :string   :default ""
    :persist? true  :gui-sync? true}

   {:key :placer-name   :nbt-key "Placer"    :type :string   :default ""
    :persist? true  :gui-sync? true}

   ;; Energy - :block-state-xf maps stored energy → discrete 0-4 BlockState level
   {:key :energy        :nbt-key "Energy"    :type :double   :default 0.0
    :persist? true  :gui-sync? true
    :block-state-prop "energy"
    :block-state-xf   (fn [e s]
                        (let [max-e (double (node-max-energy s))]
                          (min 4 (int (Math/round (* 4.0 (/ (double e)
                                                            (max 1.0 max-e))))))))}

   ;; Connection status
   {:key :enabled       :nbt-key "Enabled"   :type :boolean  :default false
    :persist? true  :gui-sync? true
    :block-state-prop "connected"}

   ;; Ephemeral charging flags (not persisted)
   {:key :charging-in   :nbt-key nil         :type :boolean  :default false
    :persist? false :gui-sync? true}

   {:key :charging-out  :nbt-key nil         :type :boolean  :default false
    :persist? false :gui-sync? true}

   ;; Tick counter (not persisted, not synced)
   {:key :update-ticker :nbt-key nil         :type :int      :default 0
    :persist? false :gui-sync? false}

   ;; Inventory (custom load/save for ItemStack handling)
   {:key :inventory     :nbt-key "NodeInventory" :type :inventory :default [nil nil]
    :persist? true  :gui-sync? false
    :load-fn load-inventory
    :save-fn save-inventory}])

(def node-default-state
  (schema/schema->default-state node-state-schema))

;; ============================================================================
;; Part 2: BlockState Properties
;; ============================================================================

(def block-state-properties
  {:energy    {:name "energy"    :type :integer :min 0 :max 4 :default 0}
   :connected {:name "connected" :type :boolean              :default false}})

;; ============================================================================
;; Part 3: Schema-Derived Functions
;; ============================================================================

(def node-scripted-load-fn
  (schema/schema->load-fn node-state-schema))

(def node-scripted-save-fn
  (schema/schema->save-fn node-state-schema))

(def ^:private update-block-state!
  (schema/schema->block-state-updater node-state-schema))

;; ============================================================================
;; Part 4: Helper Functions
;; ============================================================================

(defn- parse-node-type [block-id-or-kw]
  (let [s (if (keyword? block-id-or-kw) (name block-id-or-kw) (str block-id-or-kw))]
    (cond
      (str/includes? s "advanced") :advanced
      (str/includes? s "standard") :standard
      :else :basic)))

(defn- node-safe-state
  "Return the BE's customState, or a fresh default state seeded with node-type."
  [be block-id]
  (or (platform-be/get-custom-state be)
      (assoc node-default-state :node-type (parse-node-type block-id))))

(def ^:private node-slot-schema-id :wireless-node)
(def ^:private node-input-slot-index
  (slot-schema/slot-index node-slot-schema-id :input))
(def ^:private node-output-slot-index
  (slot-schema/slot-index node-slot-schema-id :output))
(def ^:private node-slot-indexes
  (slot-schema/all-slot-indexes node-slot-schema-id))
(def ^:private node-slot-count
  (slot-schema/tile-slot-count node-slot-schema-id))

;; ============================================================================
;; Part 4: Server-Side Tick Logic
;; ============================================================================

(defn- tick-charge-in
  "Pull energy from inventory slot 0 into the node. Returns updated state."
  [state]
  (let [input-item (get-in state [:inventory node-input-slot-index])]
    (if (and input-item (energy/is-energy-item-supported? input-item))
      (let [cur       (double (:energy state 0.0))
            max-e     (double (node-max-energy state))
            bandwidth (double (get-in node-types
                                      [(keyword (:node-type state :basic)) :bandwidth] 150))
            needed    (min bandwidth (- max-e cur))
            pulled    (energy/pull-energy-from-item input-item needed false)]
        (if (pos? pulled)
          (assoc state :energy (+ cur pulled) :charging-in true)
          (assoc state :charging-in false)))
      (assoc state :charging-in false))))

(defn- tick-charge-out
  "Push energy from node to inventory slot 1. Returns updated state."
  [state]
  (let [output-item (get-in state [:inventory node-output-slot-index])
        cur         (double (:energy state 0.0))]
    (if (and output-item (energy/is-energy-item-supported? output-item) (pos? cur))
      (let [bandwidth (double (get-in node-types
                                      [(keyword (:node-type state :basic)) :bandwidth] 150))
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
    (let [vblock      (vb/create-vnode (pos/pos-x pos) (pos/pos-y pos) (pos/pos-z pos))
          world-data  (wd/get-world-data level)
          network     (wd/get-network-by-node world-data vblock)
          connected?  (and network (not (:disposed network)))]
      (assoc state :enabled connected?))
    (catch Exception _
      (assoc state :enabled false))))

(defn node-scripted-tick-fn
  [level pos _block-state be]
  (let [block-id (platform-be/get-block-id be)
        state    (node-safe-state be block-id)
        ticker   (inc (get state :update-ticker 0))
        state    (assoc state :update-ticker ticker)
        ;; Every tick: charge in/out
        state    (try (tick-charge-in state)  (catch Exception _ state))
        state    (try (tick-charge-out state) (catch Exception _ state))
        ;; Every 20 ticks: check network + sync
        state    (if (zero? (mod ticker 20))
                   (let [state (try (tick-check-network state level pos) (catch Exception _ state))]
                     ;; Update BlockState visual (energy bar + connected glow)
                     (update-block-state! state level pos)
                     ;; Broadcast to connected GUIs
                     (try
                       (when-let [broadcast-fn (requiring-resolve 'cn.li.ac.block.wireless-node.gui/broadcast-node-state)]
                         (broadcast-fn
                          level pos
                          (-> (schema/schema->sync-payload node-state-schema state pos)
                              (assoc :max-energy (node-max-energy state)))))
                       (catch Exception _))
                     state)
                   state)]
    (platform-be/set-custom-state! be state)
    (platform-be/set-changed! be)))

;; ============================================================================
;; Part 5: Container Functions (Slot Access)
;; ============================================================================

(def ^:private node-container-fns
  {:get-size (fn [_be] node-slot-count)

   :get-item (fn [be slot]
               (get-in (or (platform-be/get-custom-state be) node-default-state)
                       [:inventory slot]))

   :set-item! (fn [be slot item]
                (let [state  (or (platform-be/get-custom-state be) node-default-state)
                      state' (assoc-in state [:inventory slot] item)]
                  (platform-be/set-custom-state! be state')))

   :remove-item (fn [be slot amount]
                  (let [state (or (platform-be/get-custom-state be) node-default-state)
                        item  (get-in state [:inventory slot])]
                    (when item
                      (let [cnt (pitem/item-get-count item)]
                        (if (<= cnt amount)
                          (do (platform-be/set-custom-state! be (assoc-in state [:inventory slot] nil)) item)
                          (pitem/item-split item amount))))))

   :remove-item-no-update (fn [be slot]
                            (let [state (or (platform-be/get-custom-state be) node-default-state)
                                  item  (get-in state [:inventory slot])]
                              (platform-be/set-custom-state! be (assoc-in state [:inventory slot] nil))
                              item))

   :clear! (fn [be]
             (platform-be/set-custom-state! be (assoc (or (platform-be/get-custom-state be) node-default-state)
                  :inventory (vec (repeat node-slot-count nil)))))

   :still-valid?          (fn [_be _player] true)
   :slots-for-face        (fn [_be _face] (int-array node-slot-indexes))

   :can-place-through-face? (fn [_be slot item _face]
              (and (= slot node-input-slot-index)
                (energy/is-energy-item-supported? item)))

   :can-take-through-face? (fn [_be slot _item _face] (= slot node-output-slot-index))})

;; ============================================================================
;; Part 6: Network Message Handlers (Server-Side)
;; ============================================================================

(defn- update-node-field!
  "Update a single field in the BE's customState."
  [be field value]
  (let [state (or (platform-be/get-custom-state be) {})]
    (platform-be/set-custom-state! be (assoc state field value))
    (try (platform-be/set-changed! be) (catch Exception _))
    be))

(defn handle-get-status
  [payload player]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)]
    (if tile
      (if-let [net (helper/get-wireless-net-by-node tile)]
        {:linked {:ssid (:ssid net)
                  :is-encrypted? (not (empty? (str (:password net))))}}
        {:linked nil})
      {:linked false})))

(defn handle-change-name
  [payload player]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)
        new-name (:node-name payload)]
    (if (and tile new-name)
      (do
        (update-node-field! tile :node-name new-name)
        {:success true})
      {:success false})))

(defn handle-change-password
  [payload player]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)
        new-password (:password payload)]
    (if (and tile new-password)
      (do
        (update-node-field! tile :password new-password)
        {:success true})
      {:success false})))

(defn handle-list-networks
  [payload player]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)]
    (if tile
      (let [linked (try (helper/get-wireless-net-by-node tile) (catch Exception _ nil))
            linked-ssid (when linked (:ssid linked))
            x (double (:pos-x payload))
            y (double (:pos-y payload))
            z (double (:pos-z payload))
            range (try (.getRange ^IWirelessNode tile) (catch Exception _ 20.0))
            nets (helper/get-nets-in-range world x y z range 100)]
        {:linked (when linked
                   {:ssid (:ssid linked)
                    :is-encrypted? (not (empty? (str (:password linked))))})
         :avail (->> nets
                     (remove (fn [net] (= (:ssid net) linked-ssid)))
                     (mapv (fn [net]
                             (let [matrix (when (:matrix net)
                                            (vb/vblock-get (:matrix net) world))]
                               {:ssid (:ssid net)
                                :is-encrypted? (not (empty? (str (:password net))))
                                :load (wireless-net/get-load net)
                                :capacity (if matrix
                                            (try (winterfaces/get-capacity matrix) (catch Exception _ 0))
                                            0)
                                :bandwidth (if matrix
                                             (try (winterfaces/get-bandwidth matrix) (catch Exception _ 0))
                                             0)
                                :range (if matrix
                                         (try (winterfaces/get-range matrix) (catch Exception _ 0.0))
                                         0.0)}))))})
      {:linked nil :avail []})))

(defn handle-connect
  [payload player]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)
        ssid (:ssid payload)
        password (:password payload)]
    (if (and world tile ssid)
      (let [world-data (world-data/get-world-data world)
            net (world-data/get-network-by-ssid world-data ssid)
            matrix (when net (vb/vblock-get (:matrix net) world))]
        (if (and net matrix)
          {:success (boolean (wireless-net/add-node! net (vb/create-vnode tile) password))}
          {:success false}))
      {:success false})))

(defn handle-disconnect
  [payload player]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)]
    (if (and world tile)
      (do
        (helper/unlink-node-from-network! tile)
        {:success true})
      {:success false})))

;; ============================================================================
;; Part 7: Block Event Handlers
;; ============================================================================

(defn handle-node-right-click [node-type]
  (fn [event-data]
    (log/info "Wireless Node (" (name node-type) ") right-clicked!")
    (let [{:keys [player world pos]} event-data
          be    (world/world-get-tile-entity world pos)
          state (when be (or (platform-be/get-custom-state be) node-default-state))]
      (if state
        (do
          (log/info "Node status:")
          (log/info "  Energy:" (:energy state) "/" (node-max-energy state))
          (log/info "  Connected:" (:enabled state))
          (log/info "  Name:" (:node-name state))
          (try
            (if-let [open-gui-by-type (requiring-resolve 'cn.li.ac.wireless.gui.registry/open-gui-by-type)]
              (let [result (open-gui-by-type player :node world pos)]
                (log/info "Opened Node GUI")
                result)
              (do (log/error "Node GUI registry function not found") nil))
            (catch Exception e
              (log/error "Failed to open Node GUI:" (ex-message e))
              nil)))
        (log/info "No tile entity found!")))))

(defn handle-node-place [node-type]
  (fn [event-data]
    (log/info "Placing Wireless Node (" (name node-type) ")")
    (let [{:keys [player world pos]} event-data
          player-name (str player)
          be          (world/world-get-tile-entity world pos)]
      (when be
        (let [state (or (platform-be/get-custom-state be) node-default-state)]
          (platform-be/set-custom-state! be (assoc state
                               :node-type   node-type
                               :placer-name player-name))))
      (log/info "Node placed by" player-name "at" pos))))

(defn handle-node-break [node-type]
  (fn [event-data]
    (log/info "Breaking Wireless Node (" (name node-type) ")")
    (let [{:keys [world pos]} event-data
          be (world/world-get-tile-entity world pos)]
      (when be
        (let [state (or (platform-be/get-custom-state be) node-default-state)]
          (doseq [item (:inventory state [])]
            (when item (log/info "Dropping item:" item))))))))

;; ============================================================================
;; Part 8: Registration
;; ============================================================================

;; Register tile logic
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

;; ============================================================================
;; Part 10: Capability Implementations
;; ============================================================================

(deftype WirelessNodeImpl [be]
  IWirelessNode

  (getEnergy [_]
    (let [state (or (platform-be/get-custom-state be) node-default-state)]
      (double (schema/get-field node-state-schema state :energy))))

  (getMaxEnergy [_]
    (let [state (or (platform-be/get-custom-state be) node-default-state)]
      (double (node-max-energy state))))

  (getBandwidth [_]
    (let [state     (or (platform-be/get-custom-state be) node-default-state)
          node-type (keyword (schema/get-field node-state-schema state :node-type))]
      (double (get-in node-types [node-type :bandwidth] 150))))

  (getCapacity [_]
    (let [state     (or (platform-be/get-custom-state be) node-default-state)
          node-type (keyword (schema/get-field node-state-schema state :node-type))]
      (int (get-in node-types [node-type :capacity] 5))))

  (getRange [_]
    (let [state     (or (platform-be/get-custom-state be) node-default-state)
          node-type (keyword (schema/get-field node-state-schema state :node-type))]
      (double (get-in node-types [node-type :range] 9))))

  (getNodeName [_]
    (let [state (or (platform-be/get-custom-state be) node-default-state)]
      (str (schema/get-field node-state-schema state :node-name))))

  (getPassword [_]
    (let [state (or (platform-be/get-custom-state be) node-default-state)]
      (str (schema/get-field node-state-schema state :password))))

  (getBlockPos [_]
    (pos/position-get-block-pos be))

  Object
  (toString [_]
    (str "WirelessNodeImpl@" (pos/position-get-block-pos be))))

(deftype ClojureEnergyImpl [be]
  IEnergyCapable

  (receiveEnergy [_ max-receive simulate]
    (let [state    (or (platform-be/get-custom-state be) node-default-state)
          cur      (double (schema/get-field node-state-schema state :energy))
          max-e    (double (node-max-energy state))
          can-recv (- max-e cur)
          actual   (min can-recv (double max-receive))]
      (when (and (not simulate) (pos? actual))
        (platform-be/set-custom-state! be (assoc state :energy (+ cur actual))))
      (int actual)))

  (extractEnergy [_ max-extract simulate]
    (let [state  (or (platform-be/get-custom-state be) node-default-state)
          cur    (double (schema/get-field node-state-schema state :energy))
          actual (min cur (double max-extract))]
      (when (and (not simulate) (pos? actual))
        (platform-be/set-custom-state! be (assoc state :energy (- cur actual))))
      (int actual)))

  (getEnergyStored [_]
    (let [state (or (platform-be/get-custom-state be) node-default-state)]
      (int (schema/get-field node-state-schema state :energy))))

  (getMaxEnergyStored [_]
    (let [state (or (platform-be/get-custom-state be) node-default-state)]
      (int (node-max-energy state))))

  (canExtract [_] true)
  (canReceive [_] true)

  Object
  (toString [_]
    (str "ClojureEnergyImpl@" (pos/position-get-block-pos be))))

;; Register capabilities
(platform-cap/declare-capability! :wireless-node IWirelessNode
  (fn [be _side] (->WirelessNodeImpl be)))

(platform-cap/declare-capability! :wireless-energy IEnergyCapable
  (fn [be _side] (->ClojureEnergyImpl be)))

;; Register for each node tier
(doseq [tile-id ["wireless-node-basic" "wireless-node-standard" "wireless-node-advanced"]]
  (tile-logic/register-tile-capability! tile-id :wireless-node)
  (tile-logic/register-tile-capability! tile-id :wireless-energy)
  (tile-logic/register-container! tile-id node-container-fns))

;; Register network handlers
(defn register-network-handlers! []
  (net-server/register-handler (msg :get-status) handle-get-status)
  (net-server/register-handler (msg :change-name) handle-change-name)
  (net-server/register-handler (msg :change-password) handle-change-password)
  (net-server/register-handler (msg :list-networks) handle-list-networks)
  (net-server/register-handler (msg :connect) handle-connect)
  (net-server/register-handler (msg :disconnect) handle-disconnect)
  (log/info "Node GUI network handlers registered"))

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
  :block-state-properties block-state-properties
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
  :block-state-properties block-state-properties
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
  :block-state-properties block-state-properties
  :on-right-click (handle-node-right-click :advanced)
  :on-place (handle-node-place :advanced)
  :on-break (handle-node-break :advanced))

;; Helper functions
(defn get-all-wireless-nodes []
  [wireless-node-basic
   wireless-node-standard
   wireless-node-advanced])

(defn init-wireless-nodes! []
  (log/info "Initialized Wireless Nodes (Design-3: customState, schema-driven):")
  (doseq [[tier cfg] node-types]
    (log/info "  -" (name tier) ": max-energy=" (:max-energy cfg)))
  (log/info "  - Capabilities :wireless-node + :wireless-energy registered"))

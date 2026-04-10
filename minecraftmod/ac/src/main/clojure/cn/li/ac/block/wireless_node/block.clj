(ns cn.li.ac.block.wireless-node.block
  "Wireless Node block - energy network node with item charging.

  This file contains:
  - Block state schema (NBT fields) - imported from schema.clj
  - Block definitions (basic/standard/advanced tiers)
  - Server-side logic (tick, NBT, container)
  - Network message handlers (server-side) - generated from schema

  Architecture:
  All persistent state lives in ScriptedBlockEntity.customState as a Clojure
  persistent map. The schema defines all fields and their serialization."
  (:require [cn.li.mcmod.block.dsl           :as bdsl]
            [cn.li.mcmod.block.tile-dsl      :as tdsl]
            [cn.li.mcmod.block.tile-logic    :as tile-logic]
            [cn.li.mcmod.block.state-schema  :as state-schema]
            [cn.li.mcmod.block.inventory-helpers :as inv-helpers]
            [cn.li.mcmod.gui.slot-schema     :as slot-schema]
            [cn.li.mcmod.gui.slot-registry   :as slot-registry]
            [cn.li.mcmod.platform.capability :as platform-cap]
            [cn.li.mcmod.platform.world      :as world]
            [cn.li.mcmod.platform.be         :as platform-be]
            [cn.li.mcmod.platform.item       :as pitem]
            [cn.li.mcmod.platform.position   :as pos]
            [cn.li.mcmod.platform.nbt        :as nbt]
            [cn.li.mcmod.network.server      :as net-server]
            [clojure.string                  :as str]
            [cn.li.ac.energy.operations      :as energy]
            [cn.li.ac.block.wireless-node.config :as node-config]
            [cn.li.ac.wireless.data.world    :as world-data]
            [cn.li.ac.wireless.core.vblock :as vb]
            [cn.li.ac.wireless.search-config :as search-config]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.ac.wireless.api        :as helper]
            [cn.li.ac.wireless.data.network       :as wireless-net]
            [cn.li.ac.wireless.gui.sync.handler :as net-helpers]
            [cn.li.ac.registry.hooks         :as hooks]
            [cn.li.ac.block.wireless-node.schema :as node-schema]
            [cn.li.mcmod.util.log            :as log])
  (:import [cn.li.acapi.wireless IWirelessNode IWirelessMatrix WirelessCapabilityKeys]
           [cn.li.acapi.energy IEnergyCapable]))

;; ============================================================================
;; Message ID Helper
;; ============================================================================

(defn- msg
  "Generate message ID for node actions."
  [action]
  (msg-registry/msg :node action))

;; ============================================================================
;; Part 1: Node Type Specifications and State Schema
;; ============================================================================

(defn node-types
  "Single source of truth for per-tier capability values."
  []
  (node-config/node-types))

(defn- node-tier
  [state]
  (keyword (:node-type state :basic)))

(defn node-max-energy
  "Derive the max-energy for a state map from its :node-type."
  [state]
  (node-config/max-energy (node-tier state)))

(defn energy->blockstate-level
  "Transform energy value to BlockState level (0-4) for visual display."
  [e s]
  (let [max-e (double (node-max-energy s))]
    (min 4 (int (Math/round (* 4.0 (/ (double e) (max 1.0 max-e))))))))

;; ============================================================================
;; Part 2: Schema-Based Generation (Server-Side)
;; ============================================================================

;; Generate from schema
(def node-state-schema
  (state-schema/filter-server-fields node-schema/unified-node-schema))

(def node-default-state
  (state-schema/schema->default-state node-state-schema))

(def node-scripted-load-fn
  (state-schema/schema->load-fn node-state-schema))

(def node-scripted-save-fn
  (state-schema/schema->save-fn node-state-schema))

(def block-state-properties
  (state-schema/extract-block-state-properties node-schema/blockstate-property-fields))

(def ^:private update-block-state!
  (state-schema/build-block-state-updater node-schema/blockstate-property-fields))

(def network-handlers
  (state-schema/build-network-handlers node-schema/network-editable-fields))

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
(defonce ^:private node-slot-schema-installed? (atom false))

(defn ensure-node-slot-schema!
  []
  (when (compare-and-set! node-slot-schema-installed? false true)
    (slot-schema/register-slot-schema!
      {:schema-id node-slot-schema-id
       :slots [{:id :input :type :energy :x 42 :y 10}
               {:id :output :type :output :x 42 :y 80}]}))
  node-slot-schema-id)

(def ^:private node-input-slot-index*
  (delay
    (ensure-node-slot-schema!)
    (slot-schema/slot-index node-slot-schema-id :input)))

(def ^:private node-output-slot-index*
  (delay
    (ensure-node-slot-schema!)
    (slot-schema/slot-index node-slot-schema-id :output)))

(def ^:private node-slot-indexes*
  (delay
    (ensure-node-slot-schema!)
    (slot-schema/all-slot-indexes node-slot-schema-id)))

(def ^:private node-slot-count*
  (delay
    (ensure-node-slot-schema!)
    (slot-registry/get-slot-count node-slot-schema-id)))

(defn- node-input-slot-index [] @node-input-slot-index*)
(defn- node-output-slot-index [] @node-output-slot-index*)
(defn- node-slot-indexes [] @node-slot-indexes*)
(defn- node-slot-count [] @node-slot-count*)

;; ============================================================================
;; Part 4: Server-Side Tick Logic
;; ============================================================================

;; Cache broadcast function to avoid requiring-resolve in hot path
(def ^:private broadcast-node-state-fn
  (delay (requiring-resolve 'cn.li.ac.block.wireless-node.gui/broadcast-node-state)))

(defn- tick-charge-in
  "Pull energy from inventory slot 0 into the node. Returns updated state."
  [state]
  (let [input-item (get-in state [:inventory (node-input-slot-index)])]
    (if (and input-item (energy/is-energy-item-supported? input-item))
      (let [cur       (double (:energy state 0.0))
            max-e     (double (node-max-energy state))
            bandwidth (double (node-config/bandwidth (node-tier state)))
            needed    (min bandwidth (- max-e cur))
            pulled    (double (energy/pull-energy-from-item input-item needed false))]
        (if (pos? pulled)
          (assoc state :energy (+ cur pulled) :charging-in true)
          (assoc state :charging-in false)))
      (assoc state :charging-in false))))

(defn- tick-charge-out
  "Push energy from node to inventory slot 1. Returns updated state."
  [state]
  (let [output-item (get-in state [:inventory (node-output-slot-index)])
        cur (double (:energy state 0.0))]
    (if (and output-item (energy/is-energy-item-supported? output-item) (pos? cur))
      (let [bandwidth (double (node-config/bandwidth (node-tier state)))
            to-charge (min bandwidth cur)
            leftover  (double (energy/charge-energy-to-item output-item to-charge false))
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
          world-data  (world-data/get-world-data level)
          network     (world-data/get-network-by-node world-data vblock)
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
          ;; Every configured interval: check network + sync
          state    (if (zero? (mod ticker (node-config/sync-interval)))
                   (let [state (try (tick-check-network state level pos) (catch Exception _ state))
                         old-sync-state (::last-broadcast-state state)
                         new-sync-state (-> (state-schema/schema->sync-payload node-state-schema state pos)
                                            (assoc :max-energy (node-max-energy state)))
                         ;; Only update BlockState if visual properties changed
                         old-level (energy->blockstate-level (:energy old-sync-state 0) state)
                         new-level (energy->blockstate-level (:energy state 0) state)
                         old-enabled (:enabled old-sync-state false)
                         new-enabled (:enabled state false)]
                     (when (or (not= new-level old-level) (not= new-enabled old-enabled))
                       (update-block-state! state level pos))
                     ;; Broadcast to connected GUIs only if state changed
                     (when (not= new-sync-state old-sync-state)
                       (try
                         (when-let [broadcast-fn @broadcast-node-state-fn]
                           (broadcast-fn level pos new-sync-state))
                         (catch Exception _)))
                     (assoc state ::last-broadcast-state new-sync-state))
                   state)
        ;; Only update BE if state actually changed
        old-state (platform-be/get-custom-state be)]
    (when (not= state old-state)
      (platform-be/set-custom-state! be state)
      (platform-be/set-changed! be))))

;; ============================================================================
;; Part 5: Container Functions (Slot Access)
;; ============================================================================

(def ^:private node-container-fns
  {:get-size (fn [_be] (node-slot-count))

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
           :inventory (vec (repeat (node-slot-count) nil)))))

   :still-valid?          (fn [_be _player] true)
  :slots-for-face        (fn [_be _face] (int-array (node-slot-indexes)))

   :can-place-through-face? (fn [_be slot item _face]
              (and (= slot (node-input-slot-index))
                (energy/is-energy-item-supported? item)))

   :can-take-through-face? (fn [_be slot _item _face] (= slot (node-output-slot-index)))})

;; ============================================================================
;; Part 6: Network Message Handlers (Server-Side)
;; ============================================================================

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

;; Network handlers for change-name and change-password are auto-generated from schema
(def handle-change-name (get network-handlers :change-name))
(def handle-change-password (get network-handlers :change-password))

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
            nets (helper/get-nets-in-range world x y z range (search-config/max-results))]
        {:linked (when linked
                   {:ssid (:ssid linked)
                    :is-encrypted? (not (empty? (str (:password linked))))})
         :avail (->> nets
                     (remove (fn [net] (= (:ssid net) linked-ssid)))
                     (mapv (fn [net]
                             (let [matrix (when (:matrix net)
                     (vb/vblock-get (:matrix net) world))
                  matrix-cap (when matrix
                                                 (platform-be/get-capability matrix WirelessCapabilityKeys/MATRIX))]
              {:ssid (:ssid net)
               :is-encrypted? (not (empty? (str (:password net))))
               :load (wireless-net/get-load net)
               :capacity (if matrix-cap
                     (try (.getMatrixCapacity ^IWirelessMatrix matrix-cap) (catch Exception _ 0))
                     0)
               :bandwidth (if matrix-cap
                      (try (.getMatrixBandwidth ^IWirelessMatrix matrix-cap) (catch Exception _ 0))
                      0)
               :range (if matrix-cap
                  (try (.getMatrixRange ^IWirelessMatrix matrix-cap) (catch Exception _ 0.0))
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

;; ============================================================================
;; Part 10: Capability Implementations
;; ============================================================================

(deftype WirelessNodeImpl [be]
  IWirelessNode

  (getEnergy [_]
    (let [state (or (platform-be/get-custom-state be) node-default-state)]
      (double (state-schema/get-field node-state-schema state :energy))))

  (getMaxEnergy [_]
    (let [state (or (platform-be/get-custom-state be) node-default-state)]
      (double (node-max-energy state))))

  (getBandwidth [_]
    (let [state     (or (platform-be/get-custom-state be) node-default-state)
          node-type (keyword (state-schema/get-field node-state-schema state :node-type))]
      (double (node-config/bandwidth node-type))))

  (getCapacity [_]
    (let [state     (or (platform-be/get-custom-state be) node-default-state)
          node-type (keyword (state-schema/get-field node-state-schema state :node-type))]
      (int (node-config/capacity node-type))))

  (getRange [_]
    (let [state     (or (platform-be/get-custom-state be) node-default-state)
          node-type (keyword (state-schema/get-field node-state-schema state :node-type))]
      (double (node-config/range-blocks node-type))))

  (getNodeName [_]
    (let [state (or (platform-be/get-custom-state be) node-default-state)]
      (str (state-schema/get-field node-state-schema state :node-name))))

  (getPassword [_]
    (let [state (or (platform-be/get-custom-state be) node-default-state)]
      (str (state-schema/get-field node-state-schema state :password))))

  (getBlockPos [_]
    (pos/position-get-block-pos be))

  Object
  (toString [_]
    (str "WirelessNodeImpl@" (pos/position-get-block-pos be))))

(deftype ClojureEnergyImpl [be]
  IEnergyCapable

  (receiveEnergy [_ max-receive simulate]
    (let [state    (or (platform-be/get-custom-state be) node-default-state)
          cur      (double (state-schema/get-field node-state-schema state :energy))
          max-e    (double (node-max-energy state))
          can-recv (- max-e cur)
          actual   (min can-recv (double max-receive))]
      (when (and (not simulate) (pos? actual))
        (platform-be/set-custom-state! be (assoc state :energy (+ cur actual))))
      (int actual)))

  (extractEnergy [_ max-extract simulate]
    (let [state  (or (platform-be/get-custom-state be) node-default-state)
          cur    (double (state-schema/get-field node-state-schema state :energy))
          actual (min cur (double max-extract))]
      (when (and (not simulate) (pos? actual))
        (platform-be/set-custom-state! be (assoc state :energy (- cur actual))))
      (int actual)))

  (getEnergyStored [_]
    (let [state (or (platform-be/get-custom-state be) node-default-state)]
      (int (state-schema/get-field node-state-schema state :energy))))

  (getMaxEnergyStored [_]
    (let [state (or (platform-be/get-custom-state be) node-default-state)]
      (int (node-max-energy state))))

  (canExtract [_] true)
  (canReceive [_] true)

  Object
  (toString [_]
    (str "ClojureEnergyImpl@" (pos/position-get-block-pos be))))

;; Register network handlers
(defn register-network-handlers! []
  (net-server/register-handler (msg :get-status) handle-get-status)
  (net-server/register-handler (msg :change-name) handle-change-name)
  (net-server/register-handler (msg :change-password) handle-change-password)
  (net-server/register-handler (msg :list-networks) handle-list-networks)
  (net-server/register-handler (msg :connect) handle-connect)
  (net-server/register-handler (msg :disconnect) handle-disconnect)
  (log/info "Node GUI network handlers registered"))

;; Helper functions
(defn get-all-wireless-nodes []
  [(bdsl/get-block "wireless-node-basic")
   (bdsl/get-block "wireless-node-standard")
   (bdsl/get-block "wireless-node-advanced")])

(defonce ^:private wireless-node-installed? (atom false))

(defn init-wireless-nodes! []
  (when (compare-and-set! wireless-node-installed? false true)
    (ensure-node-slot-schema!)
    (msg-registry/register-block-messages!
      :node
      [:get-status :change-name :change-password :list-networks :connect :disconnect])
    (tile-logic/register-tile-kind!
      :wireless-node
      {:tick-fn node-scripted-tick-fn
       :read-nbt-fn node-scripted-load-fn
       :write-nbt-fn node-scripted-save-fn})
    (tdsl/register-tile!
      (tdsl/create-tile-spec
        "wireless-node-basic"
        {:registry-name "node_basic"
         :impl :scripted
         :blocks ["wireless-node-basic"]
         :tile-kind :wireless-node}))
    (tdsl/register-tile!
      (tdsl/create-tile-spec
        "wireless-node-standard"
        {:registry-name "node_standard"
         :impl :scripted
         :blocks ["wireless-node-standard"]
         :tile-kind :wireless-node}))
    (tdsl/register-tile!
      (tdsl/create-tile-spec
        "wireless-node-advanced"
        {:registry-name "node_advanced"
         :impl :scripted
         :blocks ["wireless-node-advanced"]
         :tile-kind :wireless-node}))
    (platform-cap/declare-capability! :wireless-node IWirelessNode
      (fn [be _side] (->WirelessNodeImpl be)))
    (platform-cap/declare-capability! :wireless-energy IEnergyCapable
      (fn [be _side] (->ClojureEnergyImpl be)))
    (doseq [tile-id ["wireless-node-basic" "wireless-node-standard" "wireless-node-advanced"]]
      (tile-logic/register-tile-capability! tile-id :wireless-node)
      (tile-logic/register-tile-capability! tile-id :wireless-energy)
      (tile-logic/register-container! tile-id node-container-fns))
    (bdsl/register-block!
      (bdsl/create-block-spec
        "wireless-node-basic"
        {:registry-name "node_basic"
         :physical {:material :metal
                    :hardness 2.5
                    :resistance 6.0
                    :requires-tool true
                    :harvest-tool :pickaxe
                    :harvest-level 1
                    :sounds :metal}
         :rendering {:model-parent "minecraft:block/cube_all"}
         :block-state {:block-state-properties block-state-properties}
         :events {:on-right-click (handle-node-right-click :basic)
                  :on-place (handle-node-place :basic)
                  :on-break (handle-node-break :basic)}}))
    (bdsl/register-block!
      (bdsl/create-block-spec
        "wireless-node-standard"
        {:registry-name "node_standard"
         :physical {:material :metal
                    :hardness 2.5
                    :resistance 6.0
                    :requires-tool true
                    :harvest-tool :pickaxe
                    :harvest-level 1
                    :sounds :metal}
         :rendering {:model-parent "minecraft:block/cube_all"}
         :block-state {:block-state-properties block-state-properties}
         :events {:on-right-click (handle-node-right-click :standard)
                  :on-place (handle-node-place :standard)
                  :on-break (handle-node-break :standard)}}))
    (bdsl/register-block!
      (bdsl/create-block-spec
        "wireless-node-advanced"
        {:registry-name "node_advanced"
         :physical {:material :metal
                    :hardness 2.5
                    :resistance 6.0
                    :requires-tool true
                    :harvest-tool :pickaxe
                    :harvest-level 1
                    :sounds :metal}
         :rendering {:model-parent "minecraft:block/cube_all"}
         :block-state {:block-state-properties block-state-properties}
         :events {:on-right-click (handle-node-right-click :advanced)
                  :on-place (handle-node-place :advanced)
                  :on-break (handle-node-break :advanced)}}))
    (hooks/register-network-handler! register-network-handlers!)
    (log/info "Initialized Wireless Nodes (Design-3: customState, schema-driven):")
    (doseq [[tier cfg] (node-types)]
      (log/info "  -" (name tier) ": max-energy=" (:max-energy cfg)))
    (log/info "  - Capabilities :wireless-node + :wireless-energy registered")))

(ns cn.li.ac.block.wireless-node.logic
  "Wireless Node block logic - state management, validation, and business logic."
  (:require [clojure.string :as str]
            [cn.li.mcmod.block.state-schema :as state-schema]
            [cn.li.mcmod.block.inventory-helpers :as inv-helpers]
            [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.mcmod.gui.slot-registry :as slot-registry]
            [cn.li.mcmod.platform.item :as pitem]
            [cn.li.mcmod.platform.position :as pos]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.ac.energy.operations :as energy]
            [cn.li.ac.block.wireless-node.config :as node-config]
            [cn.li.ac.wireless.service.world-registry :as world-registry]
            [cn.li.ac.wireless.core.vblock :as vb]
            [cn.li.ac.block.wireless-node.schema :as node-schema]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; State Schema and Defaults
;; ============================================================================

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

;; ============================================================================
;; Helper Functions
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

;; ============================================================================
;; Slot Management
;; ============================================================================

(def ^:private node-slot-schema-id :wireless-node)

(defn ensure-node-slot-schema!
  []
  (slot-schema/register-slot-schema!
    {:schema-id node-slot-schema-id
     :slots [{:id :input :type :energy :x 42 :y 10}
             {:id :output :type :output :x 42 :y 80}]}))

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
;; Tick Logic
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
          world-data  (world-registry/get-world-data level)
          network     (world-registry/get-network-by-node world-data vblock)
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
;; Container Functions (Slot Access)
;; ============================================================================

(def node-container-fns
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
;; Event Handlers
;; ============================================================================

(defn handle-node-right-click [node-type]
  (fn [event-data]
    (log/info "Wireless Node (" (name node-type) ") right-clicked!")
    (let [{:keys [player world pos]} event-data
          be    (cn.li.mcmod.platform.world/world-get-tile-entity* world pos)
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
          be          (cn.li.mcmod.platform.world/world-get-tile-entity* world pos)]
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
          be (cn.li.mcmod.platform.world/world-get-tile-entity* world pos)]
      (when be
        (let [state (or (platform-be/get-custom-state be) node-default-state)]
          (doseq [item (:inventory state [])]
            (when item (log/info "Dropping item:" item))))))))

;; ============================================================================
;; Capability Implementations
;; ============================================================================

(deftype WirelessNodeImpl [be]
  cn.li.acapi.wireless.IWirelessNode

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
  cn.li.mcmod.energy.IEnergyCapable

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

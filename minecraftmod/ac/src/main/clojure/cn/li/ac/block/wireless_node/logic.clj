(ns cn.li.ac.block.wireless-node.logic
  "Wireless Node state lifecycle, tick, inventory, block events, and ownership helpers."
  (:require [clojure.string :as str]
            [cn.li.ac.block.machine.container :as machine-container]
            [cn.li.ac.block.machine.runtime :as machine-runtime]
            [cn.li.ac.block.wireless-node.schema :as node-schema]
            [cn.li.ac.energy.operations :as energy]
            [cn.li.ac.wireless.api :as wireless-api]
            [cn.li.ac.wireless.config :as node-config]
            [cn.li.ac.wireless.core.vblock :as vb]
            [cn.li.ac.wireless.data.network-lookup :as network-lookup]
            [cn.li.ac.wireless.data.network-state :as network-state]
            [cn.li.ac.wireless.data.node-conn :as node-conn]
            [cn.li.ac.wireless.data.world-registry :as world-registry]
            [cn.li.mcmod.block.state-schema :as state-schema]
            [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.position :as ppos]
            [cn.li.mcmod.platform.world :as platform-world]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; State lifecycle (from state.clj)
;; ============================================================================

(def ^:private node-rt
  (machine-runtime/schema-runtime node-schema/unified-node-schema :server-only? true))

(def node-state-schema (:server-schema node-rt))
(def node-default-state (:default-state node-rt))
(def node-scripted-load-fn (:load-fn node-rt))
(def node-scripted-save-fn (:save-fn node-rt))

(def block-state-properties
  (state-schema/extract-block-state-properties node-schema/blockstate-property-fields))

;; ── energy->blockstate-level and its dependencies MUST be defined ──
;; BEFORE update-block-state! below.  build-block-state-updater calls
;; ns-resolve on the :xf symbol at closure-creation time; if the var
;; doesn't exist yet, ns-resolve returns nil and the raw double energy
;; value is passed to IntegerProperty, which rejects it.

(defn node-tier
  [state]
  (keyword (:node-type state :basic)))

(defn node-max-energy
  "Derive the max-energy for a state map from its :node-type."
  [state]
  (node-config/max-energy (node-tier state)))

(defn energy->blockstate-level
  "Transform energy value to BlockState level (0-4) for visual display.
   Must return int (not Long) — IntegerProperty.setValue requires Integer.
   Clojure's min promotes to Long, so wrap with (int …)."
  [e s]
  (let [max-e (double (node-max-energy s))]
    (int (min 4 (Math/round (* 4.0 (/ (double e) (max 1.0 max-e))))))))

(def update-block-state!
  (state-schema/build-block-state-updater node-schema/blockstate-property-fields))

(defn node-types
  "Single source of truth for per-tier capability values."
  []
  (node-config/node-types))

(defn parse-node-type
  [block-id-or-kw]
  (let [s (if (keyword? block-id-or-kw) (name block-id-or-kw) (str block-id-or-kw))]
    (cond
      (str/includes? s "advanced") :advanced
      (str/includes? s "standard") :standard
      :else :basic)))

(defn node-safe-state
  "Return the BE's customState, or a fresh default state seeded with node-type."
  [be block-id]
  (or (platform-be/get-custom-state be)
      (assoc node-default-state :node-type (parse-node-type block-id))))

;; ============================================================================
;; Inventory / slot schema (from inventory.clj)
;; ============================================================================

(def node-slot-schema-id :wireless-node)

(def ^:private node-slot-schema-config
  {:schema-id node-slot-schema-id
   :slots [{:id :input :type :energy :x 42 :y 10}
           {:id :output :type :energy :x 42 :y 80}]})

(defonce ^:private node-slot-schema-registration
  (delay
    (slot-schema/register-slot-schema! node-slot-schema-config)))

(defn ensure-node-slot-schema!
  []
  @node-slot-schema-registration)

(defn node-input-slot-index
  []
  (slot-schema/slot-index node-slot-schema-id :input))

(defn node-output-slot-index
  []
  (slot-schema/slot-index node-slot-schema-id :output))

(defn node-slot-indexes
  []
  (slot-schema/all-slot-indexes node-slot-schema-id))

(defn node-slot-count
  []
  (slot-schema/tile-slot-count node-slot-schema-id))

(def node-container-fns
  (machine-container/make-inventory-container-fns
    {:default-state node-default-state
     :slot-count node-slot-count
     :slots-for-face (fn [_be _face] (int-array (node-slot-indexes)))
     :can-place? (fn [_be slot item _face]
                   (and (energy/is-energy-item-supported? item)
                        (or (= slot (node-input-slot-index))
                            (= slot (node-output-slot-index)))))
     :can-take? (fn [_be slot _item _face]
                  (= slot (node-output-slot-index)))}))

;; ============================================================================
;; Ownership helpers (from owner.clj)
;; ============================================================================

(defn owner-name
  "Normalize node owner from tile custom state.
  Returns an empty string when owner cannot be resolved."
  [tile-state]
  (str (get (or tile-state {}) :placer-name "")))

(defn player-name
  "Normalize player name for authorization checks.
  Returns an empty string on any lookup failure."
  [player]
  (try
    (str (or (entity/player-get-name player) ""))
    (catch Exception _
      "")))

(defn owner-authorized?
  "True when player is allowed to edit node owner-protected fields.
  Backward compatibility: accept older owner serialization that embeds the
  player's name as `...'<name>'...`."
  [owner player]
  (let [owner (str owner)
        player-name (player-name player)]
    (or (str/blank? owner)
        (= owner player-name)
        (and (not (str/blank? player-name))
             (str/includes? owner (str "'" player-name "'"))))))

;; ============================================================================
;; Tick (from tick.clj)
;; ============================================================================

(defn tick-charge-in
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

(defn tick-charge-out
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

(defn tick-check-network
  "Update :enabled flag and capacity fields based on wireless network lookup.
   :capacity = generator + receiver count on THIS node (NodeConn load).
   :max-capacity = matrix capacity of the network this node belongs to."
  [state level block-pos node-tile]
  (try
    (let [vblock (vb/create-vnode (ppos/pos-x block-pos) (ppos/pos-y block-pos) (ppos/pos-z block-pos))
          _ (wireless-api/register-node-spatial! level vblock)
          network (wireless-api/get-wireless-net-by-node node-tile)
          connected? (network-state/active? network)
          world-data (or (:world-data network)
                         (world-registry/get-world-data level))
          ;; NodeConn stores node as :node-conn type (via create-vnode-conn).
          ;; Must use matching VBlock for the node-lookup key.
          node-conn-vb (vb/create-vnode-conn node-tile)
          conn-load (if-let [conn (network-lookup/get-node-connection world-data node-conn-vb)]
                      (node-conn/get-load conn)
                      0)
          max-cap (if network (network-state/get-capacity network level) 0)]
      (assoc state :enabled connected? :capacity conn-load :max-capacity max-cap))
    (catch Exception e
      (log/stacktrace (str "[wireless-node] tick-check-network failed at " block-pos) e)
      (assoc state :enabled false :capacity 0 :max-capacity 0))))

(defn- sync-blockstate-if-changed!
  "Sync BlockState properties from BE state to world BlockState.
   Compares against the CURRENT world BlockState (not old BE state)
   to handle world reload where BlockState resets to defaults while
   BE state persists from NBT.

   Called via :after-commit! in make-tick-fn every tick the state changes."
  [_be level pos _old-state new-state _ctx]
  (when (and level pos)
    ;; Always call update-block-state! — it reads the current BlockState
    ;; internally and only writes when the values actually differ.
    (update-block-state! new-state level pos)))

(defn node-tick-state
  [state {:keys [level pos be]}]
  (let [ticker (inc (long (get state :update-ticker 0)))
        state1 (-> state
                   (assoc :update-ticker ticker)
                   (assoc :max-energy (node-max-energy state)))
        state2 (try (tick-charge-in state1) (catch Exception _ state1))
        state3 (try (tick-charge-out state2) (catch Exception _ state2))]
    (if (zero? (mod ticker (node-config/sync-interval)))
      (try
        (tick-check-network state3 level pos be)
        (catch Exception _ state3))
      state3)))

(def node-scripted-tick-fn
  "BlockState updates follow the vanilla 1.20 pattern (e.g. Furnace lit):
   :after-commit! does a cheap in-memory energy-level comparison first;
   :blockstate-updater is intentionally omitted — avoid reading world
   BlockState every tick when the visual level hasn't changed."
  (machine-runtime/make-tick-fn
    {:default-state node-default-state
     :initial-state (fn [be _ctx]
                      (node-safe-state be (platform-be/get-block-id be)))
     :tick-state node-tick-state
     :after-commit! sync-blockstate-if-changed!}))

;; ============================================================================
;; Block events
;; ============================================================================

(defn handle-node-right-click
  [_node-type]
  (machine-runtime/make-open-gui-handler :node))

(defn handle-node-place
  [node-type]
  (fn [{:keys [player world pos]}]
    (log/info "Placing Wireless Node (" (name node-type) ")")
    (let [player-name (player-name player)
          node-vb (vb/create-vnode (ppos/pos-x pos) (ppos/pos-y pos) (ppos/pos-z pos))
          be (platform-world/world-get-tile-entity* world pos)]
      (when be
        (let [state (or (platform-be/get-custom-state be) node-default-state)
              state' (assoc state :node-type node-type :placer-name player-name)]
          (machine-runtime/commit-state! be world pos state state')))
      (try
        (wireless-api/register-node-spatial! world node-vb)
        (catch Exception _))
      (log/info "Node placed by" player-name "at" pos))))

(defn handle-node-break
  [_node-type]
  (fn [{:keys [world pos]}]
    (log/info "Breaking Wireless Node")
    (let [node-vb (vb/create-vnode (ppos/pos-x pos) (ppos/pos-y pos) (ppos/pos-z pos))
          be (platform-world/world-get-tile-entity* world pos)]
      ;; Inventory items are dropped automatically by SharedScriptedBlock.onRemove
      ;; via Containers.dropContents.
      (try
        (wireless-api/unregister-node-spatial! world node-vb)
        (when be
          (wireless-api/unlink-node-from-network! be)
          (wireless-api/destroy-node-connection-for-node! be))
        (catch Exception _)))))

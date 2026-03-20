(ns cn.li.ac.block.wireless-node
  "Wireless Node block implementation - energy network node with item charging.

  State model (Design-3):
  All persistent state lives in ScriptedBlockEntity.customState as a Clojure
  persistent map.

  State map shape is defined by cn.li.ac.block.node-schema/node-state-schema.
  Do NOT hard-code field names here; add/rename/remove fields only in that
  schema and everything below updates automatically."
  (:require [cn.li.mcmod.block.dsl           :as bdsl]
            [cn.li.mcmod.block.tile-dsl      :as tdsl]
            [cn.li.mcmod.block.tile-logic    :as tile-logic]
            [cn.li.ac.block.role-impls    :as impls]
            [cn.li.ac.block.node-schema   :as nschema]
            [cn.li.mcmod.block.state-schema  :as schema]
            [cn.li.mcmod.gui.slot-schema     :as slot-schema]
            [cn.li.mcmod.platform.capability :as platform-cap]
            [cn.li.mcmod.platform.world      :as world]
            [cn.li.mcmod.platform.be         :as platform-be]
            [cn.li.mcmod.platform.position   :as pos]
            [clojure.string             :as str]
            [cn.li.ac.energy.operations   :as energy]
            [cn.li.wireless.world-data :as wd]
            [cn.li.ac.wireless.slot-schema :as slots]
            [cn.li.wireless.virtual-blocks :as vb]
            [cn.li.mcmod.util.log            :as log])
  (:import [my_mod.api.wireless IWirelessNode]
           [my_mod.api.energy   IEnergyCapable]
           [net.minecraft.world.item ItemStack]))

;; ============================================================================
;; BlockState properties declaration (used by defblock at bottom)
;; ============================================================================

(def block-state-properties
  {:energy    {:name "energy"    :type :integer :min 0 :max 4 :default 0}
   :connected {:name "connected" :type :boolean              :default false}})

;; ============================================================================
;; Schema-derived functions  (single-call derivation, executed once at load)
;; ============================================================================

;; node-scripted-load-fn :: CompoundTag -> state-map
(def node-scripted-load-fn
  (schema/schema->load-fn nschema/node-state-schema))

;; node-scripted-save-fn :: (be, CompoundTag) -> nil
(def node-scripted-save-fn
  (schema/schema->save-fn nschema/node-state-schema))

;; update-block-state! :: (state, level, pos) -> nil  (swallows exceptions)
(def ^:private update-block-state!
  (schema/schema->block-state-updater nschema/node-state-schema))






;; ============================================================================
;; Private helpers
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
      (assoc nschema/node-default-state :node-type (parse-node-type block-id))))

(def ^:private node-slot-schema-id slots/wireless-node-id)
(def ^:private node-input-slot-index
  (slot-schema/slot-index node-slot-schema-id :input))
(def ^:private node-output-slot-index
  (slot-schema/slot-index node-slot-schema-id :output))
(def ^:private node-slot-indexes
  (slot-schema/all-slot-indexes node-slot-schema-id))
(def ^:private node-slot-count
  (slot-schema/tile-slot-count node-slot-schema-id))

;; ============================================================================
;; Design-3 tick logic (functional, operates on state map)
;; ============================================================================

(defn- tick-charge-in
  "Pull energy from inventory slot 0 into the node. Returns updated state."
  [state]
  (let [input-item (get-in state [:inventory node-input-slot-index])]
    (if (and input-item (energy/is-energy-item-supported? input-item))
      (let [cur       (double (:energy state 0.0))
            max-e     (double (nschema/node-max-energy state))
            bandwidth (double (get-in nschema/node-types
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
      (let [bandwidth (double (get-in nschema/node-types
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

;; ============================================================================
;; Scripted BE adapter functions (Design-3)
;; ============================================================================

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
                       (require 'cn.li.ac.wireless.gui.node-sync)
                       ((resolve 'cn.li.ac.wireless.gui.node-sync/broadcast-node-state)
                        level pos
                        (-> (schema/schema->sync-payload nschema/node-state-schema state pos)
                            (assoc :max-energy (nschema/node-max-energy state))))
                       (catch Exception _))
                     state)
                   state)]
    (platform-be/set-custom-state! be state)
    (platform-be/set-changed! be)))




;; ============================================================================
;; Container functions (slot access via BE customState)
;; ============================================================================

(def ^:private node-container-fns
  {:get-size (fn [_be] node-slot-count)

   :get-item (fn [be slot]
               (get-in (or (platform-be/get-custom-state be) nschema/node-default-state)
                       [:inventory slot]))

   :set-item! (fn [be slot item]
                (let [state  (or (platform-be/get-custom-state be) nschema/node-default-state)
                      state' (assoc-in state [:inventory slot] item)]
                  (platform-be/set-custom-state! be state')))

   :remove-item (fn [be slot amount]
                  (let [state (or (platform-be/get-custom-state be) nschema/node-default-state)
                        item  (get-in state [:inventory slot])]
                    (when item
                      (let [cnt (.getCount ^ItemStack item)]
                        (if (<= cnt amount)
                          (do (platform-be/set-custom-state! be (assoc-in state [:inventory slot] nil)) item)
                          (.split ^ItemStack item amount))))))

   :remove-item-no-update (fn [be slot]
                            (let [state (or (platform-be/get-custom-state be) nschema/node-default-state)
                                  item  (get-in state [:inventory slot])]
                              (platform-be/set-custom-state! be (assoc-in state [:inventory slot] nil))
                              item))

   :clear! (fn [be]
             (platform-be/set-custom-state! be (assoc (or (platform-be/get-custom-state be) nschema/node-default-state)
                  :inventory (vec (repeat node-slot-count nil)))))

   :still-valid?          (fn [_be _player] true)
     :slots-for-face        (fn [_be _face] (int-array node-slot-indexes))

   :can-place-through-face? (fn [_be slot item _face]
              (and (= slot node-input-slot-index)
                (energy/is-energy-item-supported? item)))

     :can-take-through-face? (fn [_be slot _item _face] (= slot node-output-slot-index))})

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
          state (when be (or (platform-be/get-custom-state be) nschema/node-default-state))]
      (if state
        (do
          (log/info "Node status:")
          (log/info "  Energy:" (:energy state) "/" (nschema/node-max-energy state))
          (log/info "  Connected:" (:enabled state))
          (log/info "  Name:" (:node-name state))
          (try
            (if-let [open-node-gui (requiring-resolve 'cn.li.ac.wireless.gui.registry/open-node-gui)]
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
        (let [state (or (platform-be/get-custom-state be) nschema/node-default-state)]
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
        (let [state (or (platform-be/get-custom-state be) nschema/node-default-state)]
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
  (log/info "Initialized Wireless Nodes (Design-3: customState, schema-driven):")
  (doseq [[tier cfg] nschema/node-types]
    (log/info "  -" (name tier) ": max-energy=" (:max-energy cfg)))
  (log/info "  - Capabilities :wireless-node + :wireless-energy registered"))

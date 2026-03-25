(ns cn.li.ac.block.wireless-matrix.block
  "Wireless Matrix block - 2x2x2 multiblock structure.

  This file contains:
  - Block state schema (NBT fields)
  - Block definition (DSL, properties, events)
  - Server-side logic (tick, NBT, container)
  - Network message handlers (server-side)

  Architecture:
  All persistent state lives in ScriptedBlockEntity.customState as a Clojure
  persistent map. The schema defines all fields and their serialization."
  (:require [cn.li.mcmod.block.dsl :as bdsl]
            [cn.li.mcmod.block.tile-dsl :as tdsl]
            [cn.li.mcmod.block.tile-logic :as tile-logic]
            [cn.li.mcmod.block.state-schema :as schema]
            [cn.li.mcmod.platform.position :as pos]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.item :as pitem]
            [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.mcmod.platform.capability :as platform-cap]
            [cn.li.mcmod.platform.world :as world]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.network.server :as net-server]
            [cn.li.ac.item.constraint-plate :as plate]
            [cn.li.ac.item.mat-core :as core]
            [cn.li.ac.wireless.gui.network-handler-helpers :as net-helpers]
            [cn.li.ac.wireless.helper :as helper]
            [cn.li.ac.wireless.network :as wireless-net]
            [cn.li.ac.wireless.gui.message-registry :as msg-registry]
            [cn.li.ac.registry.hooks :as hooks]
            [cn.li.ac.block.wireless-matrix.schema :as matrix-schema]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.acapi.wireless IWirelessMatrix]))

;; ============================================================================
;; Message ID Helper
;; ============================================================================

(msg-registry/register-block-messages!
  :matrix
  [:gather-info :init :change-ssid :change-password])

(defn- msg
  "Generate message ID for matrix actions."
  [action]
  (msg-registry/msg :matrix action))

;; ============================================================================
;; Part 1: State Schema (imported from schema.clj)
;; ============================================================================

;; Derived from schema
(def matrix-default-state
  (schema/schema->default-state matrix-schema/unified-matrix-schema))

(def matrix-scripted-load-fn
  (schema/schema->load-fn matrix-schema/unified-matrix-schema))

(def matrix-scripted-save-fn
  (schema/schema->save-fn matrix-schema/unified-matrix-schema))

;; ============================================================================
;; Part 2: Helper Functions
;; ============================================================================

(defn- safe-state
  "Return the customState map from a BE, falling back to defaults."
  [be]
  (or (platform-be/get-custom-state be) matrix-default-state))

(def ^:private matrix-slot-schema-id :wireless-matrix)
(def ^:private matrix-plate-slot-indexes
  (slot-schema/slot-indexes-by-type matrix-slot-schema-id :plate))
(def ^:private matrix-core-slot-index
  (slot-schema/slot-index matrix-slot-schema-id :core))
(def ^:private matrix-slot-indexes
  (slot-schema/all-slot-indexes matrix-slot-schema-id))
(def ^:private matrix-slot-count
  (slot-schema/tile-slot-count matrix-slot-schema-id))

;; ============================================================================
;; Part 3: Business Logic
;; ============================================================================

(defn- set-inv-slot [state slot item] (assoc-in state [:inventory slot] item))

(defn- recalculate-plate-count
  "Count non-nil items in matrix plate slots."
  [state]
  (assoc state :plate-count
         (count (for [slot matrix-plate-slot-indexes
                      :let [stk (get-in state [:inventory slot])]
                      :when (and stk (not (pitem/item-is-empty? stk)))]
                  slot))))

(defn- recalculate-core-level
  "Update :core-level from core slot."
  [state]
  (assoc state :core-level
         (core/get-core-level (get-in state [:inventory matrix-core-slot-index]))))

(defn recalculate-counts
  "Recalculate plate-count and core-level from current inventory."
  [state]
  (-> state recalculate-plate-count recalculate-core-level))

(defn is-working?
  "Is matrix operational? Requires 3 plates + a core."
  [state]
  (and (> (:core-level state 0) 0)
       (= (:plate-count state 0) (count matrix-plate-slot-indexes))))

(defn get-plate-count
  "Return plate count (0–3) from block entity state. For use by renderers.
  Returns 0 when be is nil."
  [be]
  (if be (get (safe-state be) :plate-count 0) 0))

(defn get-core-level
  "Return core level (0–3) from block entity state. For use by renderers.
  Returns 0 when be is nil."
  [be]
  (if be (get (safe-state be) :core-level 0) 0))

;; ============================================================================
;; Part 3.5: Capability Implementation (must be before MatrixJavaProxy)
;; ============================================================================

(deftype WirelessMatrixImpl [be]
  IWirelessMatrix

  (getMatrixCapacity [_]
    (let [state   (or (platform-be/get-custom-state be) matrix-default-state)
          plates  (schema/get-field matrix-schema/unified-matrix-schema state :plate-count)
          core-lv (schema/get-field matrix-schema/unified-matrix-schema state :core-level)]
      (if (and (> core-lv 0) (= plates 3))
        (int (* 8 core-lv))
        0)))

  (getMatrixBandwidth [_]
    (let [state   (or (platform-be/get-custom-state be) matrix-default-state)
          plates  (schema/get-field matrix-schema/unified-matrix-schema state :plate-count)
          core-lv (schema/get-field matrix-schema/unified-matrix-schema state :core-level)]
      (if (and (> core-lv 0) (= plates 3))
        (double (* core-lv core-lv 60))
        0.0)))

  (getMatrixRange [_]
    (let [state   (or (platform-be/get-custom-state be) matrix-default-state)
          plates  (schema/get-field matrix-schema/unified-matrix-schema state :plate-count)
          core-lv (schema/get-field matrix-schema/unified-matrix-schema state :core-level)]
      (if (and (> core-lv 0) (= plates 3))
        (double (* 24 (Math/sqrt core-lv)))
        0.0)))

  (getSsid [_]
    (let [state (or (platform-be/get-custom-state be) matrix-default-state)]
      (str (schema/get-field matrix-schema/unified-matrix-schema state :ssid))))

  (getPassword [_]
    (let [state (or (platform-be/get-custom-state be) matrix-default-state)]
      (str (schema/get-field matrix-schema/unified-matrix-schema state :password))))

  (getPlacerName [_]
    (let [state (or (platform-be/get-custom-state be) matrix-default-state)]
      (str (schema/get-field matrix-schema/unified-matrix-schema state :placer-name))))

  Object
  (toString [_]
    (str "WirelessMatrixImpl@" (pos/position-get-block-pos be))))

;; ============================================================================
;; Part 4: Java Accessor Bridge
;; ============================================================================

(definterface IMatrixJavaProxy
  (^String  getPlacerName [])
  (^long    getMatrixCapacity [])
  (^long    getMatrixBandwidth [])
  (^double  getMatrixRange [])
  (^long    getLoad [])
  (^Object  getPos []))

(deftype MatrixJavaProxy [be]
  IMatrixJavaProxy
  (getPlacerName    [_] (str (:placer-name (safe-state be))))
  (getMatrixCapacity [_]
    (long (.getMatrixCapacity ^IWirelessMatrix (->WirelessMatrixImpl be))))
  (getMatrixBandwidth [_]
    (long (.getMatrixBandwidth ^IWirelessMatrix (->WirelessMatrixImpl be))))
  (getMatrixRange [_]
    (double (.getMatrixRange ^IWirelessMatrix (->WirelessMatrixImpl be))))
  (getLoad [_] 0)
  (getPos  [_] (pos/position-get-block-pos be))
  Object
  (toString [_] (str "MatrixJavaProxy@" (pos/position-get-block-pos be))))

;; ============================================================================
;; Part 5: Server-Side Tick Logic
;; ============================================================================

(defn matrix-scripted-tick-fn
  "Tick: read state from BE, do work, write state back."
  [level pos _state be]
  (let [state (safe-state be)
        ticker (inc (get state :update-ticker 0))
        state (assoc state :update-ticker ticker)
        broken? (atom false)
        state (if (and (zero? (:sub-id state 0))
                       (zero? (mod ticker 15)))
                (try
                  (if-let [broadcast-fn (requiring-resolve 'cn.li.ac.block.wireless-matrix.gui/broadcast-matrix-state)]
                    (let [impl ^IWirelessMatrix (->WirelessMatrixImpl be)
                          old-sync-state (::last-broadcast-state state)
                          new-sync-state (-> (schema/schema->sync-payload matrix-schema/unified-matrix-schema state pos)
                                             (assoc :is-working  (is-working? state)
                                                    :capacity    (.getMatrixCapacity impl)
                                                    :bandwidth   (.getMatrixBandwidth impl)
                                                    :range       (.getMatrixRange impl)))]
                      (when (not= new-sync-state old-sync-state)
                        (broadcast-fn level pos new-sync-state))
                      (assoc state ::last-broadcast-state new-sync-state))
                    state)
                  (catch Exception e
                    (log/debug "Matrix sync skipped:" (ex-message e))
                    state))
                state)]
    ;; Verify structure every 20 ticks
    (when (zero? (mod ticker 20))
      (try
        (let [block-spec (bdsl/get-block :wireless-matrix)]
          (when (and block-spec
                     (zero? (:sub-id state 0)))
            (let [origin (or (:multi-block-origin block-spec) {:x 0 :y 0 :z 0})
                  positions (if-let [custom-pos (:multi-block-positions block-spec)]
                              (bdsl/calculate-multi-block-positions custom-pos origin)
                              (bdsl/calculate-multi-block-positions (:multi-block-size block-spec) origin))
                  part-pos-map {:x (pos/pos-x pos) :y (pos/pos-y pos) :z (pos/pos-z pos)}
                  master-found? (some (fn [rel-pos]
                                        (let [master-map (bdsl/get-multi-block-master-pos part-pos-map rel-pos)]
                                          (bdsl/is-multi-block-complete? level master-map block-spec)))
                                      positions)]
              (when (not master-found?)
                (reset! broken? true)
                (log/info "Matrix structure broken at" pos)
                ;; Drop inventory items
                (doseq [[idx item] (map-indexed vector (:inventory state []))]
                  (when item (log/info "Dropping item from slot" idx)))
                ;; Keep runtime ticker but clear persistent matrix state
                (platform-be/set-custom-state! be (assoc matrix-default-state :update-ticker ticker))))))
        (catch Exception e
          (log/error "Error verifying matrix structure:" (ex-message e)))))
    (when-not @broken?
      (platform-be/set-custom-state! be (assoc state :update-ticker ticker)))))

;; ============================================================================
;; Part 6: Container Functions (Slot Access)
;; ============================================================================

(def ^:private matrix-container-fns
  {:get-size (fn [_be] matrix-slot-count)

   :get-item (fn [be slot]
               (get-in (safe-state be) [:inventory slot]))

   :set-item! (fn [be slot item]
                (let [state  (safe-state be)
                      state' (-> state
                                 (set-inv-slot slot item)
                                 recalculate-counts)]
                  (platform-be/set-custom-state! be state')))

   :remove-item (fn [be slot amount]
                  (let [state (safe-state be)
                        item  (get-in state [:inventory slot])]
                    (when item
                      (let [cnt (pitem/item-get-count item)]
                        (if (<= cnt amount)
                          (do (platform-be/set-custom-state! be (-> state (set-inv-slot slot nil) recalculate-counts))
                              item)
                          (let [result (pitem/item-split item amount)]
                            (platform-be/set-custom-state! be (recalculate-counts state))
                            result))))))

   :remove-item-no-update (fn [be slot]
                            (let [state (safe-state be)
                                  item  (get-in state [:inventory slot])]
                              (platform-be/set-custom-state! be (-> state (set-inv-slot slot nil) recalculate-counts))
                              item))

   :clear! (fn [be]
             (platform-be/set-custom-state! be (assoc (safe-state be) :inventory (vec (repeat matrix-slot-count nil))
                                                         :plate-count 0 :core-level 0)))

   :still-valid? (fn [_be _player] true)

   :slots-for-face (fn [_be _face] (int-array matrix-slot-indexes))

   :can-place-through-face? (fn [_be slot item _face]
                               (case (slot-schema/slot-type matrix-slot-schema-id slot)
                                 :plate (plate/is-constraint-plate? item)
                                 :core (core/is-mat-core? item)
                                 false))

   :can-take-through-face? (fn [_be _slot _item _face] true)})

;; ============================================================================
;; Part 7: Network Message Handlers (Server-Side)
;; ============================================================================

(defn- get-wireless-network
  "Get Matrix's bound wireless network."
  [tile]
  (helper/get-wireless-net-by-matrix tile))

(defn- is-owner?
  "Check if player is the Matrix owner."
  [^IWirelessMatrix tile player]
  (let [player-name (entity/player-get-name player)
        placer-name (.getPlacerName tile)]
    (= (str placer-name) (str player-name))))

(defn handle-gather-info
  "Handle MSG_GATHER_INFO message - query network information.

  Request: {:pos-x :pos-y :pos-z}
  Response: {:ssid :password :load :initialized :owner :max-capacity :range :bandwidth}"
  [payload player]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)]
    (if-let [network (and tile (get-wireless-network tile))]
      ;; Network exists
      {:ssid (:ssid network)
       :password (:password network)
       :owner (str (.getPlacerName ^IWirelessMatrix tile))
       :load (wireless-net/get-load network)
       :max-capacity (.getMatrixCapacity ^IWirelessMatrix tile)
       :range (.getMatrixRange ^IWirelessMatrix tile)
       :bandwidth (.getMatrixBandwidth ^IWirelessMatrix tile)
       :initialized true}
      ;; Network not created
      {:ssid nil
       :password nil
       :owner (if tile (str (.getPlacerName ^IWirelessMatrix tile)) "Unknown")
       :load 0
       :max-capacity (if tile (.getMatrixCapacity ^IWirelessMatrix tile) 16)
       :range (if tile (.getMatrixRange ^IWirelessMatrix tile) 64.0)
       :bandwidth (if tile (.getMatrixBandwidth ^IWirelessMatrix tile) 100)
       :initialized false})))

(defn handle-init-network
  "Handle MSG_INIT message - initialize wireless network.
  Only owner can execute.

  Request: {:pos-x :pos-y :pos-z :ssid :password}
  Response: {:success boolean}"
  [payload player]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)
        {:keys [ssid password]} payload]
    (if (and tile (is-owner? tile player))
      (try
        {:success (boolean (helper/create-network! tile ssid password))}
        (catch Exception e
          (log/error "Failed to initialize network:" (ex-message e))
          {:success false}))
      {:success false})))

(defn handle-change-ssid
  "Handle MSG_CHANGE_SSID message - change network SSID.
  Only owner can execute.

  Request: {:pos-x :pos-y :pos-z :new-ssid}
  Response: {:success boolean}"
  [payload player]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)
        new-ssid (:new-ssid payload)]
    (if (and tile (is-owner? tile player))
      (if-let [network (get-wireless-network tile)]
        (try
          (let [old-ssid (:ssid network)]
            (wireless-net/reset-ssid! network new-ssid)
            (swap! (:net-lookup (:world-data network)) dissoc old-ssid)
            (swap! (:net-lookup (:world-data network)) assoc new-ssid network)
            {:success true})
          (catch Exception e
            (log/error "Failed to change SSID:" (ex-message e))
            {:success false}))
        {:success false})
      {:success false})))

(defn handle-change-password
  "Handle MSG_CHANGE_PASSWORD message - change network password.
  Only owner can execute.

  Request: {:pos-x :pos-y :pos-z :new-password}
  Response: {:success boolean}"
  [payload player]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)
        new-password (:new-password payload)]
    (if (and tile (is-owner? tile player))
      (if-let [network (get-wireless-network tile)]
        (try
          (wireless-net/reset-password! network new-password)
          {:success true}
          (catch Exception e
            (log/error "Failed to change password:" (ex-message e))
            {:success false}))
        {:success false})
      {:success false})))

;; ============================================================================
;; Part 8: Block Event Handlers
;; ============================================================================

(defn handle-matrix-right-click []
  (fn [event-data]
    (log/info "Wireless Matrix right-clicked!")
    (let [{:keys [player world pos sneaking]} event-data
          be         (world/world-get-tile-entity world pos)
          state      (when be (safe-state be))]
      (if state
        (if-not sneaking
          (do
            (log/info "Opening Matrix GUI")
            (log/info "  Plates:" (:plate-count state))
            (log/info "  Core Level:" (:core-level state))
            (log/info "  Working:" (is-working? state))
            (try
              (if-let [open-gui-by-type (requiring-resolve 'cn.li.ac.wireless.gui.registry/open-gui-by-type)]
                (let [result (open-gui-by-type player :matrix world pos)]
                  (log/info "Opened Matrix GUI")
                  result)
                (do (log/error "Failed to open Matrix GUI: open-gui-by-type not resolved") nil))
              (catch Exception e
                (log/error "Failed to open Matrix GUI:" (ex-message e)) nil)))
          (log/info "Sneaking - no action"))
        (log/info "No tile entity found!")))))

(defn handle-matrix-place []
  (fn [event-data]
    (log/info "Placing Wireless Matrix")
    (let [{:keys [player world pos]} event-data
          player-name (str player)
          be (world/world-get-tile-entity world pos)]
      (when be
        (let [state (or (platform-be/get-custom-state be) matrix-default-state)]
          (platform-be/set-custom-state! be (assoc state :placer-name player-name))))
      (log/info "Matrix placed by" player-name "at" pos))))

(defn handle-matrix-break []
  (fn [event-data]
    (log/info "Breaking Wireless Matrix")
    (let [{:keys [world pos]} event-data
          be (world/world-get-tile-entity world pos)]
      (when be
        (let [state (safe-state be)]
          (doseq [[idx item] (map-indexed vector (:inventory state []))]
            (when item (log/info "Dropping item from slot" idx ":" item))))))))

;; ============================================================================
;; Part 9: Registration
;; ============================================================================

;; Register tile logic
(tile-logic/register-tile-kind!
  :wireless-matrix
  {:tick-fn matrix-scripted-tick-fn
   :read-nbt-fn matrix-scripted-load-fn
   :write-nbt-fn matrix-scripted-save-fn})

(def wireless-matrix-tile
  (tdsl/register-tile!
    (tdsl/create-tile-spec
      "wireless-matrix"
      {:registry-name "matrix"
       :impl :scripted
       :blocks ["wireless-matrix" "wireless-matrix-part"]
       :tile-kind :wireless-matrix})))

;; Register capability and container
(platform-cap/declare-capability! :wireless-matrix IWirelessMatrix
  (fn [be _side] (->WirelessMatrixImpl be)))

(tile-logic/register-tile-capability! "wireless-matrix" :wireless-matrix)
(tile-logic/register-container! "wireless-matrix" matrix-container-fns)

;; Register network handlers
(defn register-network-handlers!
  "Register all network message handlers."
  []
  (net-server/register-handler
    (msg :gather-info)
    handle-gather-info)

  (net-server/register-handler
    (msg :init)
    handle-init-network)

  (net-server/register-handler
    (msg :change-ssid)
    handle-change-ssid)

  (net-server/register-handler
    (msg :change-password)
    handle-change-password)

  (log/info "Matrix network handlers registered"))

;; Register block definition
(bdsl/defmultiblock 'wireless-matrix
  :multi-block {:positions [[0 0 1] [1 0 1] [1 0 0]
                            [0 1 0] [0 1 1] [1 1 1] [1 1 0]]
                :rotation-center [1.0 0 1.0]}
  :common {:material :stone
           :hardness 3.0
           :resistance 6.0
           :requires-tool true
           :harvest-tool :pickaxe
           :harvest-level 1
           :light-level 1.0
           :sounds :stone}
  :controller {:registry-name "matrix"
               :flat-item-icon? true
               :on-right-click (handle-matrix-right-click)
               :on-place (handle-matrix-place)
               :on-break (handle-matrix-break)}
  :part {:registry-name "matrix_part"
         :model-parent "minecraft:block/block"})

;; ============================================================================
;; Part 10: Auto-Registration Hooks
;; ============================================================================

;; Register network handlers with hook system
(hooks/register-network-handler! register-network-handlers!)

;; Register client renderer
(hooks/register-client-renderer! 'cn.li.ac.block.wireless-matrix.render/init!)

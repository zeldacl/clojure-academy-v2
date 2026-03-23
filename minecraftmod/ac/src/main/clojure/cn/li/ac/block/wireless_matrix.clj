(ns cn.li.ac.block.wireless-matrix
  "Wireless Matrix block implementation - 2x2x2 multiblock structure.

  State model (Design-3):
  All persistent state lives in ScriptedBlockEntity.customState as a Clojure
  persistent map.

  State map shape is defined by cn.li.ac.block.matrix-schema/matrix-state-schema.
  Do NOT hard-code field names here; add/rename/remove fields only in that
  schema and everything below updates automatically."
  (:require [cn.li.mcmod.block.dsl :as bdsl]
            [cn.li.mcmod.block.tile-dsl :as tdsl]
            [cn.li.mcmod.block.tile-logic :as tile-logic]
            [cn.li.ac.block.role-impls :as impls]
            [cn.li.ac.block.matrix-schema :as mschema]
            [cn.li.mcmod.block.state-schema :as schema]
            [cn.li.mcmod.platform.position :as pos]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.item :as pitem]
            [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.mcmod.platform.capability :as platform-cap]
            [cn.li.mcmod.platform.world :as world]
            [cn.li.ac.item.constraint-plate :as plate]
            [cn.li.ac.item.mat-core :as core]
            [cn.li.ac.wireless.slot-schema :as slots]
            [cn.li.ac.wireless.gui.matrix-sync :as sync]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.acapi.wireless IWirelessMatrix]))

;; ============================================================================
;; Schema-derived functions  (single-call derivation, executed once at load)
;; ============================================================================

;; matrix-scripted-load-fn :: CompoundTag -> state-map
(def matrix-scripted-load-fn
  (schema/schema->load-fn mschema/matrix-state-schema))

;; matrix-scripted-save-fn :: (be, CompoundTag) -> nil
(def matrix-scripted-save-fn
  (schema/schema->save-fn mschema/matrix-state-schema))

;; ============================================================================
;; Private helpers
;; ============================================================================

(defn- safe-state
  "Return the customState map from a BE, falling back to defaults."
  [be]
  (or (platform-be/get-custom-state be) mschema/matrix-default-state))

(def ^:private matrix-slot-schema-id slots/wireless-matrix-id)
(def ^:private matrix-plate-slot-indexes
  (slot-schema/slot-indexes-by-type matrix-slot-schema-id :plate))
(def ^:private matrix-core-slot-index
  (slot-schema/slot-index matrix-slot-schema-id :core))
(def ^:private matrix-slot-indexes
  (slot-schema/all-slot-indexes matrix-slot-schema-id))
(def ^:private matrix-slot-count
  (slot-schema/tile-slot-count matrix-slot-schema-id))

;; ============================================================================
;; Inventory helpers (operating on the state map directly)
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
;; Java accessor bridge
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
    (long (.getMatrixCapacity ^ IWirelessMatrix (impls/->WirelessMatrixImpl be))))
  (getMatrixBandwidth [_]
    (long (.getMatrixBandwidth ^ IWirelessMatrix (impls/->WirelessMatrixImpl be))))
  (getMatrixRange [_]
    (double (.getMatrixRange ^ IWirelessMatrix (impls/->WirelessMatrixImpl be))))
  (getLoad [_] 0)
  (getPos  [_] (pos/position-get-block-pos be))
  Object
  (toString [_] (str "MatrixJavaProxy@" (pos/position-get-block-pos be))))

;; ============================================================================
;; Tile lifecycle hooks (full-state path)
;; ============================================================================

(defn matrix-scripted-tick-fn
  "Tick: read state from BE, do work, write state back."
  [level pos _state be]
  (let [state (safe-state be)
        ticker (inc (get state :update-ticker 0))
        state (assoc state :update-ticker ticker)
        broken? (atom false)]
    ;; Sync to clients every 15 ticks (tracked via update-ticker in state)
    (when (and (zero? (:sub-id state 0))
               (zero? (mod ticker 15)))
      (try
        (let [impl ^ IWirelessMatrix (impls/->WirelessMatrixImpl be)]
          (sync/broadcast-matrix-state level pos
            (-> (schema/schema->sync-payload mschema/matrix-state-schema state pos)
                (assoc :is-working  (is-working? state)
                       :capacity    (.getMatrixCapacity impl)
                       :bandwidth   (.getMatrixBandwidth impl)
                       :range       (.getMatrixRange impl)))))
        (catch Exception e
          (log/debug "Matrix sync skipped:" ((ex-message e))))))
    ;; Verify structure every 20 ticks
    (when (zero? (mod ticker 20))
      (try
        (let [block-spec (bdsl/get-block :wireless-matrix)]
          (when (and block-spec
                     (zero? (:sub-id state 0)))
            ;; Determine master position from this part's position by
            ;; trying all relative positions defined in the block spec.
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
                ;; Keep runtime ticker but clear persistent matrix state.
                (platform-be/set-custom-state! be (assoc mschema/matrix-default-state :update-ticker ticker))))))
        (catch Exception e
          (log/error "Error verifying matrix structure:" ((ex-message e))))))
    (when-not @broken?
      (platform-be/set-custom-state! be (assoc state :update-ticker ticker)))))

;; ============================================================================
;; Container functions (slot access via BE customState)
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
;; Tile DSL registration
;; ============================================================================

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

;; Register Capability and Container
(platform-cap/declare-capability! :wireless-matrix IWirelessMatrix
  (fn [be _side] (impls/->WirelessMatrixImpl be)))

(tile-logic/register-tile-capability! "wireless-matrix" :wireless-matrix)
(tile-logic/register-container! "wireless-matrix" matrix-container-fns)

;; ============================================================================
;; Block interaction handlers
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
              (if-let [open-matrix-gui (requiring-resolve 'cn.li.ac.wireless.gui.registry/open-matrix-gui)]
                (let [result (open-matrix-gui player world pos)]
                  (log/info "Opened Matrix GUI")
                  result)
                (do (log/error "Failed to open Matrix GUI: open-matrix-gui not resolved") nil))
              (catch Exception e
                (log/error "Failed to open Matrix GUI:" ((ex-message e))) nil)))
          (log/info "Sneaking - no action"))
        (log/info "No tile entity found!")))))

(defn handle-matrix-place []
  (fn [event-data]
    (log/info "Placing Wireless Matrix")
    (let [{:keys [player world pos]} event-data
          player-name (str player)
          be (world/world-get-tile-entity world pos)]
      ;; 结构放置/part 铺设逻辑已下沉到 mcmod.block.multiblock-core。
      ;; ac 层仅记录业务字段。
      (when be
        (let [state (or (platform-be/get-custom-state be) mschema/matrix-default-state)]
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
;; Block Definition
;; ============================================================================

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
               :on-right-click (handle-matrix-right-click)
               :on-place (handle-matrix-place)
               :on-break (handle-matrix-break)}
  :part {:registry-name "matrix_part"
         :model-parent "minecraft:block/block"})



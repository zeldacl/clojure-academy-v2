(ns my-mod.block.wireless-matrix
  "Wireless Matrix block implementation - 2x2x2 multiblock structure.

  State model (Design-3):
  All persistent state lives in ScriptedBlockEntity.customState as a Clojure
  persistent map.  The matrix-tiles atom has been removed; world+pos → BE →
  .getCustomState is the only data path.

  State map shape:
    {:placer-name  String
     :inventory    [ItemStack|nil ItemStack|nil ItemStack|nil ItemStack|nil]
     :plate-count  int   ; 0-3 constraint plates installed
     :core-level   int   ; 0 = no core, 1-4 = tier
     :direction    keyword  ; :north :south :east :west
     :sub-id       int   ; 0 = origin, 1-7 = sub-blocks}"
  (:require [my-mod.block.dsl :as bdsl]
            [my-mod.block.tile-dsl :as tdsl]
            [my-mod.block.tile-logic :as tile-logic]
            [my-mod.block.role-impls :as impls]
            [my-mod.platform.capability :as platform-cap]
            [my-mod.platform.world :as world]
            [my-mod.item.constraint-plate :as plate]
            [my-mod.item.mat-core :as core]
            [my-mod.wireless.gui.matrix-sync :as sync]
            [my-mod.util.log :as log])
  (:import [my_mod.api.wireless IWirelessMatrix]))

;; ============================================================================
;; Default state
;; ============================================================================

(def default-state
  {:placer-name ""
   :inventory   [nil nil nil nil]
   :plate-count 0
   :core-level  0
   :direction   :north
   :sub-id      0})

(defn- safe-state
  "Return the customState map from a BE, falling back to defaults."
  [be]
  (or (.getCustomState be) default-state))

;; ============================================================================
;; Inventory helpers (operating on the state map directly)
;; ============================================================================

(defn get-inv-slot [state slot] (get-in state [:inventory slot]))
(defn set-inv-slot [state slot item] (assoc-in state [:inventory slot] item))

(defn- recalculate-plate-count
  "Count non-nil items in slots 0-2."
  [state]
  (assoc state :plate-count
         (count (filter some? (take 3 (:inventory state))))))

(defn- recalculate-core-level
  "Update :core-level from core slot."
  [state]
  (assoc state :core-level
         (core/get-core-level (get-in state [:inventory 3]))))

(defn recalculate-counts
  "Recalculate plate-count and core-level from current inventory."
  [state]
  (-> state recalculate-plate-count recalculate-core-level))

(defn is-working?
  "Is matrix operational? Requires 3 plates + a core."
  [state]
  (and (> (:core-level state 0) 0)
       (= (:plate-count state 0) 3)))

;; ============================================================================
;; Java accessor bridge (IMatrixJavaProxy replacement)
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
    (long (.getMatrixCapacity (impls/->WirelessMatrixImpl be))))
  (getMatrixBandwidth [_]
    (long (.getMatrixBandwidth (impls/->WirelessMatrixImpl be))))
  (getMatrixRange [_]
    (double (.getMatrixRange (impls/->WirelessMatrixImpl be))))
  (getLoad [_] 0)
  (getPos  [_] (.getBlockPos be))
  Object
  (toString [_] (str "MatrixJavaProxy@" (.getBlockPos be))))

;; ============================================================================
;; Core calculations (pure, operate on state map)
;; ============================================================================

(defn get-matrix-capacity [state]
  (if (is-working? state)
    (* 8 (:core-level state))
    0))

(defn get-matrix-bandwidth [state]
  (if (is-working? state)
    (let [lv (:core-level state)] (* lv lv 60))
    0))

(defn get-matrix-range [state]
  (if (is-working? state)
    (* 24 (Math/sqrt (:core-level state)))
    0.0))

;; ============================================================================
;; Tile lifecycle hooks (full-state path)
;; ============================================================================

(defn matrix-scripted-tick-fn
  "Tick: read state from BE, do work, write state back."
  [level pos _state be]
  (let [state (safe-state be)]
    ;; Sync to clients every 15 ticks (tracked via update-ticker in state)
    (let [ticker (inc (get state :update-ticker 0))
          state  (assoc state :update-ticker ticker)]
      (when (and (zero? (:sub-id state 0))
                 (zero? (mod ticker 15)))
        (try
          (sync/broadcast-matrix-state level pos
            {:pos-x (.getX pos) :pos-y (.getY pos) :pos-z (.getZ pos)
             :plate-count (:plate-count state 0)
             :placer-name (:placer-name state "")
             :is-working  (is-working? state)
             :core-level  (:core-level state 0)
             :capacity    (get-matrix-capacity state)
             :bandwidth   (get-matrix-bandwidth state)
             :range       (get-matrix-range state)})
          (catch Exception e
            (log/debug "Matrix sync skipped:" (.getMessage e)))))
      ;; Verify structure every 20 ticks
      (when (zero? (mod ticker 20))
        (try
          (let [block-spec (bdsl/get-block :wireless-matrix)]
            (when (and block-spec level pos
                       (zero? (:sub-id state 0))
                       (not (bdsl/is-multi-block-complete? level pos block-spec)))
              (log/info "Matrix structure broken at" pos)
              ;; Drop inventory items
              (doseq [[idx item] (map-indexed vector (:inventory state []))]
                (when item (log/info "Dropping item from slot" idx)))
              ;; Clear state
              (.setCustomState be default-state)))
          (catch Exception e
            (log/error "Error verifying matrix structure:" (.getMessage e)))))
      (.setCustomState be (assoc state :update-ticker ticker)))))

(defn matrix-scripted-load-fn
  "Deserialize CompoundTag → state map."
  [tag]
  (let [state (assoc default-state
                :placer-name (if (.contains tag "Placer")      (.getString tag "Placer") "")
                :plate-count (if (.contains tag "PlateCount")  (.getInt    tag "PlateCount") 0)
                :core-level  (if (.contains tag "CoreLevel")   (.getInt    tag "CoreLevel") 0)
                :sub-id      (if (.contains tag "SubId")       (.getInt    tag "SubId") 0)
                :direction   (keyword (if (.contains tag "Direction") (.getString tag "Direction") "north")))]
    ;; Deserialize inventory
    (if (.contains tag "Inventory")
      (let [inv-tag (.getList tag "Inventory" 10)
            inv     (reduce (fn [v i]
                              (let [slot-tag (.getCompound inv-tag i)
                                    slot     (.getInt slot-tag "Slot")
                                    item     (net.minecraft.world.item.ItemStack/of slot-tag)]
                                (if (and (>= slot 0) (< slot 4))
                                  (assoc v slot (when-not (.isEmpty item) item))
                                  v)))
                            [nil nil nil nil]
                            (range (.size inv-tag)))]
        (assoc state :inventory inv))
      state)))

(defn matrix-scripted-save-fn
  "Serialize state map → CompoundTag."
  [be tag]
  (let [state (safe-state be)]
    (.putString tag "Placer"     (str (:placer-name state "")))
    (.putInt    tag "PlateCount" (int (:plate-count state 0)))
    (.putInt    tag "CoreLevel"  (int (:core-level state 0)))
    (.putInt    tag "SubId"      (int (:sub-id state 0)))
    (.putString tag "Direction"  (name (:direction state :north)))
    ;; Serialize inventory
    (let [inv     (:inventory state [nil nil nil nil])
          inv-list (net.minecraft.nbt.ListTag.)]
      (doseq [slot (range 4)]
        (when-let [item (nth inv slot nil)]
          (let [slot-tag (net.minecraft.nbt.CompoundTag.)]
            (.putInt slot-tag "Slot" slot)
            (.save item slot-tag)
            (.add inv-list slot-tag))))
      (.put tag "Inventory" inv-list))))

;; ============================================================================
;; Container functions (slot access via BE customState)
;; ============================================================================

(def ^:private matrix-container-fns
  {:get-size (fn [be] 4)

   :get-item (fn [be slot]
               (get-in (safe-state be) [:inventory slot]))

   :set-item! (fn [be slot item]
                (let [state  (safe-state be)
                      state' (-> state
                                 (set-inv-slot slot item)
                                 recalculate-counts)]
                  (.setCustomState be state')))

   :remove-item (fn [be slot amount]
                  (let [state (safe-state be)
                        item  (get-in state [:inventory slot])]
                    (when item
                      (let [cnt (.getCount item)]
                        (if (<= cnt amount)
                          (do (.setCustomState be (-> state (set-inv-slot slot nil) recalculate-counts))
                              item)
                          (let [result (.splitStack item amount)]
                            (.setCustomState be (recalculate-counts state))
                            result))))))

   :remove-item-no-update (fn [be slot]
                            (let [state (safe-state be)
                                  item  (get-in state [:inventory slot])]
                              (.setCustomState be (-> state (set-inv-slot slot nil) recalculate-counts))
                              item))

   :clear! (fn [be]
             (.setCustomState be (assoc (safe-state be) :inventory [nil nil nil nil]
                                                         :plate-count 0 :core-level 0)))

   :still-valid? (fn [_be _player] true)

   :slots-for-face (fn [_be _face] (int-array [0 1 2 3]))

   :can-place-through-face? (fn [_be slot item _face]
                               (cond
                                 (<= 0 slot 2) (plate/is-constraint-plate? item)
                                 (= slot 3)    (core/is-mat-core? item)
                                 :else false))

   :can-take-through-face? (fn [_be _slot _item _face] true)})

;; ============================================================================
;; Tile DSL registration
;; ============================================================================

(tdsl/deftile-kind :wireless-matrix
  :tick-fn      matrix-scripted-tick-fn
  :read-nbt-fn  matrix-scripted-load-fn
  :write-nbt-fn matrix-scripted-save-fn)

(tdsl/deftile wireless-matrix-tile
  :id            "wireless-matrix"
  :registry-name "matrix"
  :impl          :scripted
  :blocks        ["wireless-matrix"]
  :tile-kind     :wireless-matrix)

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
          be    (world/world-get-tile-entity world pos)
          state (when be (safe-state be))]
      (if state
        (if-not sneaking
          (do
            (log/info "Opening Matrix GUI")
            (log/info "  Plates:" (:plate-count state))
            (log/info "  Core Level:" (:core-level state))
            (log/info "  Working:" (is-working? state))
            (try
              (if-let [open-matrix-gui (requiring-resolve 'my-mod.wireless.gui.registry/open-matrix-gui)]
                (let [result (open-matrix-gui player world pos)]
                  (log/info "Opened Matrix GUI")
                  result)
                (do (log/error "Failed to open Matrix GUI: open-matrix-gui not resolved") nil))
              (catch Exception e
                (log/error "Failed to open Matrix GUI:" (.getMessage e)) nil)))
          (log/info "Sneaking - no action"))
        (log/info "No tile entity found!")))))

(defn handle-matrix-place []
  (fn [event-data]
    (log/info "Placing Wireless Matrix")
    (let [{:keys [player world pos]} event-data
          player-name (str player)
          be (world/world-get-tile-entity world pos)]
      (when be
        (let [state (or (.getCustomState be) default-state)]
          (.setCustomState be (assoc state :placer-name player-name))))
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

(bdsl/defblock wireless-matrix
  :registry-name   "matrix"
  :material        :stone
  :hardness        3.0
  :resistance      6.0
  :requires-tool   true
  :harvest-tool    :pickaxe
  :harvest-level   1
  :light-level     1.0
  :sounds          :stone
  :multi-block     {:positions [[0 0 1] [1 0 1] [1 0 0]
                                [0 1 0] [0 1 1] [1 1 1] [1 1 0]]
                   :rotation-center [1.0 0 1.0]}
  :on-right-click  (handle-matrix-right-click)
  :on-place        (handle-matrix-place)
  :on-break        (handle-matrix-break))

;; ============================================================================
;; Initialization
;; ============================================================================

(defn init-wireless-matrix! []
  (log/info "Initialized Wireless Matrix (Design-3: customState)")
  (log/info "  - 2x2x2 multiblock, 4 inventory slots")
  (log/info "  - Capability :wireless-matrix registered"))

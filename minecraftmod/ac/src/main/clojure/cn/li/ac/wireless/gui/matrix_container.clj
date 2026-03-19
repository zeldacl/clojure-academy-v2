(ns my-mod.wireless.gui.matrix-container
  "Wireless Matrix GUI Container - handles 4 slots and multiblock data sync.

  State model (Design-3): tile-entity is a ScriptedBlockEntity; all slot data
  is read from / written to (.getCustomState be) / (.setCustomState be new-state)."
  (:require [my-mod.util.log :as log]
            [my-mod.block.wireless-matrix :as wm]
            [my-mod.block.matrix-schema :as mschema]
            [my-mod.gui.slot-schema :as slot-schema]
            [my-mod.item.constraint-plate :as plate]
            [my-mod.item.mat-core :as core]
            [my-mod.wireless.slot-schema :as slots]
            [my-mod.wireless.gui.container-common :as common]
            [my-mod.wireless.gui.container-move-common :as move-common]
            [my-mod.wireless.gui.container-schema :as schema]
            [my-mod.wireless.gui.matrix-fields :as mf]
            [my-mod.wireless.gui.sync-helpers :as sync-helpers]
            [my-mod.platform.be :as platform-be]))

;; ============================================================================
;; Field Schema — single source of truth for all atom fields
;;
;; To add/remove/rename a field, only edit this vector.
;; create-container, get-sync-data, apply-sync-data!, on-close, and the
;; client-side field-mappings all derive from it automatically.
;;
;; :key         - keyword used as the container map key
;; :init        - (fn [tile-state]) -> initial atom value
;; :sync?       - true = included in container<->container sync data
;; :coerce      - type coercion applied when writing back from sync data
;; :close-reset - value the atom is reset to in on-close
;; ============================================================================

;; Field schema is defined in matrix-fields ns to avoid circular dependencies.
(def matrix-fields mf/matrix-fields)

(defn sync-field-mappings
  "Return the field-mappings vector for apply-sync-payload-template!."
  []
  (mf/sync-field-mappings))

;; ============================================================================
;; Container Creation
;; ============================================================================

(defn- resolve-state
  "Resolve the state map from either a ScriptedBlockEntity or an existing map.
  Returns [be state] where be may be nil for legacy map input."
  [tile]
  (if (map? tile)
    [nil tile]
    (try
      (let [state (or (platform-be/get-custom-state tile) mschema/matrix-default-state)]
        [tile state])
      (catch Exception e
        (log/warn "Could not resolve customState from BE:" (.getMessage e))
        [tile {}]))))

(defn create-container
  "Create a Matrix GUI container instance.

  Args:
  - tile: ScriptedBlockEntity or legacy Clojure map
  - player: Player who opened GUI

  Returns: MatrixContainer map"
  [tile player]
  (let [[be state] (resolve-state tile)
        proxy      (if be
                     (wm/->MatrixJavaProxy be)
                     (wm/->MatrixJavaProxy tile))]
    (merge {:tile-entity    (or be tile)
            :tile-java      proxy
            :player         player
            :container-type :matrix}
           (schema/build-atoms matrix-fields state))))

;; ============================================================================
;; Slot Management
;; ============================================================================

(def ^:private matrix-slot-schema-id slots/wireless-matrix-id)

(defn- matrix-slot-count
  []
  (slot-schema/tile-slot-count matrix-slot-schema-id))

(defn- plate-slot-indexes
  []
  (slot-schema/slot-indexes-by-type matrix-slot-schema-id :plate))

(defn- core-slot-index
  []
  (slot-schema/slot-index matrix-slot-schema-id :core))

(defn get-slot-count
  "Get total tile slot count for matrix container."
  [_container]
  (matrix-slot-count))

(defn is-plate-slot?
  "Check if slot is a plate slot."
  [slot-index]
  (slot-schema/slot-type? matrix-slot-schema-id slot-index :plate))

(defn is-core-slot?
  "Check if slot is the core slot."
  [slot-index]
  (slot-schema/slot-type? matrix-slot-schema-id slot-index :core))

(defn can-place-item?
  "Check if item can be placed in slot using slot type metadata."
  [_container slot-index item-stack]
  (case (slot-schema/slot-type matrix-slot-schema-id slot-index)
    :plate (plate/is-constraint-plate? item-stack)
    :core (core/is-mat-core? item-stack)
    false))

(defn get-slot-item
  "Get item from slot. Reads from BE customState if tile-entity is a BE."
  [container slot-index]
  (common/get-slot-item-be container slot-index))

(defn set-slot-item!
  "Set item in slot. Writes to BE customState, running recalculate-counts after write."
  [container slot-index item-stack]
  (common/set-slot-item-be! container slot-index item-stack
                             mschema/matrix-default-state
                             wm/recalculate-counts))

(defn slot-changed!
  "Called when slot contents change - triggers multiblock revalidation"
  [_container slot-index]
  (log/info "Matrix container slot" slot-index "changed")
  (log/info "Revalidating matrix structure..."))

;; ============================================================================
;; Data Synchronization
;; ============================================================================

(defn count-plates
  "Count number of plate items in slots"
  [container]
  (reduce (fn [count slot-idx]
            (if (get-slot-item container slot-idx)
              (inc count)
              count))
          0
          (plate-slot-indexes)))

(defn get-core-level
  "Get core tier level from core slot item

  Returns: int 0-4 (0 if no core)"
  [container]
  (let [core-item (get-slot-item container (core-slot-index))]
    (if (and core-item (core/is-mat-core? core-item))
      (core/get-core-level core-item)
      0)))

(defn calculate-matrix-stats
  "Calculate matrix stats based on core and plates

  Returns: Map with :capacity, :bandwidth, :range"
  [core-level plate-count]
  (let [base-capacity  (* core-level 50000)
        base-bandwidth (* core-level 1000)
        base-range     (* core-level 16.0)
        plate-mult     (+ 1.0 (* plate-count 0.2))
        capacity       (int (* base-capacity plate-mult))
        bandwidth      (int (* base-bandwidth plate-mult))
        range          (* base-range plate-mult)]
    {:capacity capacity
     :bandwidth bandwidth
     :range range}))

(defn sync-to-client!
  "Update container data from tile entity (server -> client).

  Called every tick on server side. Network capacity queries are throttled
  to every 5 seconds (100 ticks) to reduce performance impact."
  [container]
  (let [plates   (count-plates container)
        core-lvl (get-core-level container)
        working? (> core-lvl 0)]

    (reset! (:core-level container) core-lvl)
    (reset! (:plate-count container) plates)
    (reset! (:is-working container) working?)

    (sync-helpers/with-throttled-sync! (:sync-ticker container) 100
      (fn []
        (let [stats (calculate-matrix-stats core-lvl plates)]
          (reset! (:bandwidth container) (:bandwidth stats))
          (reset! (:range container) (:range stats))
          (sync-helpers/query-matrix-network-capacity! container stats))))))

(defn get-sync-data
  "Get container→client sync data. Derived from matrix-fields schema.

  Returns: map of all :sync? true fields with their current values"
  [container]
  (schema/get-sync-data matrix-fields container))

(defn apply-sync-data!
  "Apply sync data from server into container atoms. Uses schema :coerce fns.

  Args:
  - container: MatrixContainer map
  - data:      map of synced values (from get-sync-data)"
  [container data]
  (schema/apply-sync-data! matrix-fields container data))

;; ============================================================================
;; Container Validation
;; ============================================================================

(defn still-valid?
  "Check if container is still valid for player"
  [container player]
  (common/still-valid? container player))

;; ============================================================================
;; Container Update Tick
;; ============================================================================

(defn tick!
  "Called every tick on server side. Updates synced data and handles multiblock logic."
  [container]
  (sync-to-client! container))

;; ============================================================================
;; Button Actions
;; ============================================================================

(def button-toggle-working 0)
(def button-eject-core 1)
(def button-eject-plates 2)

(defn handle-button-click!
  "Handle button click from client"
  [container button-id _data]
  (case (int button-id)
    0
    (log/info "Toggled matrix working state")

    1
    (do
      (set-slot-item! container (core-slot-index) nil)
      (log/info "Ejected matrix core"))

    2
    (do
      (doseq [slot-idx (plate-slot-indexes)]
        (set-slot-item! container slot-idx nil))
      (log/info "Ejected all plates"))

    (log/warn "Unknown button ID:" button-id)))

;; ============================================================================
;; Quick Move (Shift+Click)
;; ============================================================================

(def ^:private quick-move-config slots/wireless-matrix-quick-move-config)

(defn quick-move-stack
  "Handle shift-click on slot."
  [container slot-index player-inventory-start]
  (move-common/quick-move-with-rules
    container
    slot-index
    player-inventory-start
    quick-move-config))

;; ============================================================================
;; Container Lifecycle
;; ============================================================================

(defn on-close
  "Cleanup when container is closed. Resets all atom fields per schema."
  [container]
  (log/debug "Closing wireless matrix container")
  (schema/reset-atoms! matrix-fields container))

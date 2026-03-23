(ns cn.li.ac.wireless.gui.node-container
  "Wireless Node GUI Container - handles server-side inventory and data sync"
  (:require [cn.li.ac.energy.operations :as energy-stub]
            [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.ac.wireless.slot-schema :as slots]
            [cn.li.ac.wireless.gui.container-common :as common]
            [cn.li.ac.wireless.gui.container-move-common :as move-common]
            [cn.li.ac.wireless.gui.container-schema :as schema]
            [cn.li.ac.wireless.gui.sync-helpers :as sync-helpers]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.util.log :as log]))

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
;; :payload-key - (optional) tile-broadcast packet key when different from :key
;;                e.g. tile sends :node-name but container stores as :ssid
;; :coerce      - type coercion applied when writing back from sync data
;; :close-reset - value the atom is reset to in on-close
;; ============================================================================

(def node-fields
  [{:key :energy        :init (fn [s] (int (get s :energy 0.0)))          :sync? true  :coerce int     :close-reset 0}
   {:key :max-energy    :init (fn [s] (int (get s :max-energy 15000)))     :sync? true  :coerce int     :close-reset 0}
   {:key :node-type     :init (fn [s] (keyword (get s :node-type :basic))) :sync? true  :coerce keyword :close-reset :basic}
   {:key :is-online     :init (fn [s] (boolean (get s :enabled false)))    :sync? true  :payload-key :enabled :coerce boolean :close-reset false}
   {:key :ssid          :init (fn [s] (str (get s :node-name "Unnamed")))  :sync? true  :payload-key :node-name :coerce str :close-reset ""}
   {:key :password      :init (fn [s] (str (get s :password "")))          :sync? true  :coerce str     :close-reset ""}
   {:key :transfer-rate :init (fn [_] 0)                                   :sync? true  :coerce int     :close-reset 0}
   {:key :capacity      :init (fn [_] 0)                                   :sync? true  :coerce int     :close-reset 0}
   {:key :max-capacity  :init (fn [_] 0)                                   :sync? true  :coerce int     :close-reset 0}
   {:key :charge-ticker :init (fn [_] 0)                                   :sync? false :coerce int     :close-reset 0}
   {:key :sync-ticker   :init (fn [_] 0)                                   :sync? false :coerce int     :close-reset 0}
   ;; Tab index for multi-page TechUI: 0 = inv-window (slots enabled), >=1 = other panels (slots disabled)
   {:key :tab-index     :init (fn [_] 0)                                   :sync? true  :coerce int     :close-reset 0}])

(defn sync-field-mappings
  "Return the field-mappings vector for apply-sync-payload-template!.
  Derived automatically from node-fields — no manual maintenance needed."
  []
  (schema/sync-field-mappings node-fields))

;; ============================================================================
;; Container Creation
;; ============================================================================

(defn- resolve-state
  "Resolve [be state] from either a ScriptedBlockEntity or a legacy map."
  [tile]
  (if (map? tile)
    [nil tile]
    (try
      [tile (or (platform-be/get-custom-state tile) {})]
      (catch Exception e
        (log/warn "Could not resolve customState from BE:" ((ex-message e)))
        [tile {}]))))

(defn create-container
  "Create a Node GUI container instance.

  Args:
  - tile: ScriptedBlockEntity or legacy Clojure state map
  - player: Player who opened GUI

  Returns: NodeContainer map"
  [tile player]
  (let [[be state] (resolve-state tile)
        entity     (or be tile)]
    (merge {:tile-entity    entity
            :player         player
            :container-type :node}
           (schema/build-atoms node-fields state))))

;; ============================================================================
;; Slot Management
;; ============================================================================

(def ^:private node-slot-schema-id slots/wireless-node-id)

(defn- tile-state [tile] (common/get-tile-state tile))

(defn get-slot-count
  "Get total tile slot count for node."
  [_container]
  (slot-schema/tile-slot-count node-slot-schema-id))

(defn get-owner
  "Get node owner name"
  [container]
  (let [tile (:tile-entity container)]
    (:placer-name (tile-state tile))))

(defn can-place-item?
  "Check if item can be placed in slot

  Input slot: items with energy capability
  Output slot: no direct placement"
  [_container slot-index item-stack]
  (case (slot-schema/slot-type node-slot-schema-id slot-index)
    :energy (energy-stub/is-energy-item-supported? item-stack)
    :output false
    false))

(defn get-slot-item
  "Get item from slot. Reads from BE customState when tile-entity is a BE."
  [container slot-index]
  (common/get-slot-item-be container slot-index))

(defn set-slot-item!
  "Set item in slot. Writes to BE customState when tile-entity is a BE."
  [container slot-index item-stack]
  (common/set-slot-item-be! container slot-index item-stack {} identity))

(defn slot-changed!
  "Called when slot contents change"
  [_container slot-index]
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
    (reset! (:energy container)     (int (get state :energy 0.0)))
    (reset! (:max-energy container) (int (get state :max-energy 15000)))
    (reset! (:is-online container)  (boolean (get state :enabled false)))
    (reset! (:ssid container)       (str (get state :node-name "Unnamed")))
    (reset! (:password container)   (str (get state :password "")))

    (sync-helpers/with-throttled-sync! (:sync-ticker container) 100
      (fn [] (sync-helpers/query-node-network-capacity! container)))

    (let [rate (cond
                 (and (get state :charging-in false) (get state :charging-out false)) 200
                 (get state :charging-in false)  100
                 (get state :charging-out false) 100
                 :else 0)]
      (reset! (:transfer-rate container) rate))))

(defn get-sync-data
  "Get container→client sync data. Derived from node-fields schema.

  Returns: map of all :sync? true fields with their current values"
  [container]
  (schema/get-sync-data node-fields container))

(defn apply-sync-data!
  "Apply sync data from server into container atoms. Uses schema :coerce fns.

  Args:
  - container: NodeContainer map
  - data:      map of synced values (from get-sync-data)"
  [container data]
  (schema/apply-sync-data! node-fields container data))

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
      (case (int button-id)
        0
        (let [state  (or (platform-be/get-custom-state tile) {})
              state' (update state :enabled not)]
          (platform-be/set-custom-state! tile state')
          (log/info "Toggled node connection:" (:enabled state')))

        1
        (when-let [new-ssid (:ssid data)]
          (let [state  (or (platform-be/get-custom-state tile) {})
                state' (assoc state :node-name new-ssid)]
            (platform-be/set-custom-state! tile state')
            (log/info "Set node SSID to:" new-ssid)))

        2
        (when-let [new-password (:password data)]
          (let [state  (or (platform-be/get-custom-state tile) {})
                state' (assoc state :password new-password)]
            (platform-be/set-custom-state! tile state')
            (log/info "Set node password")))

        (log/warn "Unknown button ID:" button-id)))))

;; ============================================================================
;; Quick Move (Shift+Click)
;; ============================================================================

(def ^:private quick-move-config slots/wireless-node-quick-move-config)

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
  (log/debug "Closing wireless node container")
  (schema/reset-atoms! node-fields container))

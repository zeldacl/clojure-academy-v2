(ns my-mod.wireless.gui.solar-container
  "Solar Generator GUI container (server-side + synced atoms).

  Reads tile state via getCustomState like node/matrix containers —
  no Java reflection needed."
  (:require [my-mod.gui.slot-schema :as slot-schema]
            [my-mod.wireless.slot-schema :as slots]
            [my-mod.energy.operations :as energy-ops]
            [my-mod.wireless.gui.container-common :as common]
            [my-mod.wireless.gui.container-schema :as schema]))

(def ^:private solar-slot-schema-id slots/solar-gen-id)

;; ============================================================================
;; Field Schema — single source of truth for all atom fields
;;
;; To add/remove/rename a field, only edit this vector.
;; create-container, get-sync-data, apply-sync-data!, sync-to-client!, and
;; on-close all derive from it automatically.
;;
;; :key         - keyword used as the container map key (matches tile state key)
;; :init        - (fn [tile-state]) -> initial atom value
;; :sync?       - true = included in container<->container get/apply-sync-data
;; :coerce      - type coercion applied when writing back from sync data
;; :close-reset - value the atom is reset to in on-close
;; ============================================================================

(def solar-fields
  [{:key :energy     :init (fn [s] (double (get s :energy 0.0)))     :sync? true  :coerce double :close-reset 0.0}
   {:key :max-energy :init (fn [s] (double (get s :max-energy 1000.0))) :sync? true  :coerce double :close-reset 0.0}
   {:key :status     :init (fn [s] (str (get s :status "STOPPED")))  :sync? true  :coerce str    :close-reset ""}
   {:key :gen-speed  :init (fn [s] (double (get s :gen-speed 0.0)))  :sync? true  :coerce double :close-reset 0.0}
   ;; Tabbed TechUI support (0=inv, >=1=other pages). Not synced via container packets; server tab state is synced by set-tab message.
   {:key :tab-index  :init (fn [_] 0)                                :sync? false :coerce int    :close-reset 0}
   {:key :sync-ticker :init (fn [_] 0)                               :sync? false :coerce int    :close-reset 0}])

;; ============================================================================
;; Container Creation
;; ============================================================================

(defn create-container
  "Create Solar Generator container instance.

  tile: platform-specific ScriptedBlockEntity; state is read via getCustomState."
  [tile player]
  (let [state (or (common/get-tile-state tile) {})]
    (merge {:tile-entity    tile
            :player         player
            :container-type :solar}
           (schema/build-atoms solar-fields state))))

(defn get-slot-count
  "Get total tile slot count for solar container."
  [_container]
  (slot-schema/tile-slot-count solar-slot-schema-id))

(defn can-place-item?
  "Solar slot accepts energy items only."
  [_container _slot-index item-stack]
  (energy-ops/is-energy-item-supported? item-stack))

(defn get-slot-item
  "Get item from tile slot (Design-3 BE customState aware)."
  [container slot-index]
  (common/get-slot-item-be container slot-index))

(defn set-slot-item!
  "Set item in tile slot (Design-3 BE customState aware)."
  [container slot-index item-stack]
  (common/set-slot-item-be! container slot-index item-stack {} identity))

(defn slot-changed!
  "Slot change hook for parity with node/matrix containers."
  [_container _slot-index]
  nil)

;; ============================================================================
;; Validation
;; ============================================================================

(defn still-valid?
  "Best-effort validity check.
  Keep permissive to avoid breaking when player/world APIs vary."
  [_container _player]
  true)

;; ============================================================================
;; Data Synchronization
;; ============================================================================

(defn sync-to-client!
  "Update synced atoms from tile entity custom state (server -> client).
  Reads from getCustomState like node/matrix — no reflection needed."
  [container]
  (let [state (or (common/get-tile-state (:tile-entity container)) {})]
    (reset! (:energy container)     (double (get state :energy 0.0)))
    (reset! (:max-energy container) (double (get state :max-energy 1000.0)))
    (reset! (:status container)     (str (get state :status "STOPPED")))
    (reset! (:gen-speed container)  (double (get state :gen-speed 0.0)))))

(defn get-sync-data
  "Get container→client sync data. Derived from solar-fields schema."
  [container]
  (schema/get-sync-data solar-fields container))

(defn apply-sync-data!
  "Apply sync data from server into container atoms. Uses schema :coerce fns."
  [container data]
  (schema/apply-sync-data! solar-fields container data))

;; ============================================================================
;; Container Tick
;; ============================================================================

(defn tick!
  "Container tick; called by platform menu bridge."
  [container]
  (swap! (:sync-ticker container) inc)
  (sync-to-client! container))

;; ============================================================================
;; Button Actions
;; ============================================================================

(defn handle-button-click!
  [_container _button-id _player]
  nil)

;; ============================================================================
;; Container Lifecycle
;; ============================================================================

(defn on-close
  "Cleanup when container is closed. Resets all atom fields per schema."
  [container]
  (schema/reset-atoms! solar-fields container))

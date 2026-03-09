(ns my-mod.wireless.gui.matrix-fields
  "Field schema definition for MatrixContainer.

  Kept in a separate namespace so both matrix-container and matrix-sync can
  require it without creating a circular dependency.

  Note: fields whose values require wm/* computations (is-working, capacity,
  bandwidth, range) start at zero/false and are populated immediately by the
  first sync-to-client! tick, so no wm import is needed here."
  (:require [my-mod.wireless.gui.container-schema :as schema]))

;; ============================================================================
;; Field Schema — single source of truth for all MatrixContainer atom fields
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

(def matrix-fields
  [{:key :core-level   :init (fn [s] (:core-level s 0))    :sync? true  :coerce int     :close-reset 0}
   {:key :plate-count  :init (fn [s] (:plate-count s 0))   :sync? true  :coerce int     :close-reset 0}
   {:key :is-working   :init (fn [_] false)                 :sync? true  :coerce boolean :close-reset false}
   {:key :capacity     :init (fn [_] 0)                     :sync? true  :coerce int     :close-reset 0}
   {:key :max-capacity :init (fn [_] 0)                     :sync? true  :coerce int     :close-reset 0}
   {:key :bandwidth    :init (fn [_] 0)                     :sync? true  :coerce long    :close-reset 0}
   {:key :range        :init (fn [_] 0.0)                   :sync? true  :coerce double  :close-reset 0.0}
   {:key :sync-ticker  :init (fn [_] 0)                     :sync? false :coerce int     :close-reset 0}])

(defn sync-field-mappings
  "Return the field-mappings vector for apply-sync-payload-template!.
  Derived automatically from matrix-fields — no manual maintenance needed."
  []
  (schema/sync-field-mappings matrix-fields))

(ns cn.li.mcmod.gui.schema-builders
  "CLIENT-ONLY: Schema-driven GUI generation utilities.

  Must be loaded via side-checked requiring-resolve from platform layer.

  Provides functions to auto-generate GUI container atoms, sync functions,
  and lifecycle handlers from unified schema definitions."
  (:require [cn.li.mcmod.platform.be :as pbe]))

(defn build-gui-atoms
  "Generate GUI container atoms from unified schema.

  Args:
    schema - Vector of field definitions
    tile - BlockEntity or state map

  Returns: Map of {container-key atom} for all gui-sync? and gui-only? fields"
  [schema tile]
  (let [state (if (map? tile)
                tile
                (or (pbe/get-custom-state tile) {}))]
    (into {}
      (for [field schema
            :when (or (:gui-sync? field) (:gui-only? field))
            :let [k (:gui-container-key field (:key field))
                  init-fn (or (:gui-init field)
                              (fn [s] (get s (:key field) (:default field))))]]
        [k (atom (init-fn state))]))))

(defn build-sync-to-client-fn
  "Generate sync-to-client! function from schema.

  Returns: (fn [container] -> updates GUI atoms from tile state)

  The returned function:
  - Reads tile state via get-custom-state
  - Updates gui-sync? atoms with coerced values
  - Only resets atoms when value changes (prevents unnecessary re-renders)"
  [schema]
  (fn [container]
    (let [tile (:tile-entity container)
          state (if (map? tile)
                  tile
                  (or (pbe/get-custom-state tile) {}))]
      ;; Reset gui-sync? atoms only if value changed
      (doseq [field schema
              :when (:gui-sync? field)
              :let [container-key (:gui-container-key field (:key field))
                    state-key (:key field)
                    coerce-fn (or (:gui-coerce field) identity)
                    value (get state state-key (:default field))]]
        (when-let [atom-ref (get container container-key)]
          (let [new-val (coerce-fn value)]
            (when (not= @atom-ref new-val)
              (reset! atom-ref new-val))))))))

(defn build-get-sync-data-fn
  "Generate get-sync-data function from schema.

  Returns: (fn [container] -> map of sync data)

  Extracts current values from all gui-sync? atoms."
  [schema]
  (fn [container]
    (into {}
      (for [field schema
            :when (:gui-sync? field)
            :let [k (:gui-container-key field (:key field))]]
        [k (when-let [a (get container k)] @a)]))))

(defn build-apply-sync-data-fn
  "Generate apply-sync-data! function from schema.

  Returns: (fn [container data] -> updates GUI atoms from sync payload)

  Used to apply server-sent sync data to client-side GUI atoms."
  [schema]
  (fn [container data]
    (doseq [field schema
            :when (:gui-sync? field)
            :let [k (:gui-container-key field (:key field))
                  coerce-fn (or (:gui-coerce field) identity)]]
      (when-let [atom-ref (get container k)]
        (when (contains? data k)
          (reset! atom-ref (coerce-fn (get data k))))))))

(defn build-on-close-fn
  "Generate on-close function from schema.

  Returns: (fn [container] -> resets GUI atoms to close-reset values)

  Resets all fields with :gui-close-reset to their default values."
  [schema]
  (fn [container]
    (doseq [field schema
            :when (contains? field :gui-close-reset)
            :let [k (:gui-container-key field (:key field))
                  reset-val (:gui-close-reset field)]]
      (when-let [atom-ref (get container k)]
        (reset! atom-ref reset-val)))))

(defn build-sync-field-mappings
  "Generate sync field mappings from schema.

  Returns: Map of {payload-key container-key}

  Maps server payload keys to client container keys for sync."
  [schema]
  (into {}
    (for [field schema
          :when (:gui-sync? field)
          :let [container-key (:gui-container-key field (:key field))
                payload-key (:gui-payload-key field (:key field))]]
      [payload-key container-key])))

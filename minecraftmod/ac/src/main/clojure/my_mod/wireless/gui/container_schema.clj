(ns my-mod.wireless.gui.container-schema
  "Schema-driven utilities for GUI container atom fields.

  A field schema entry is a map with the following keys:
    :key         - keyword, the container map key and default payload key
    :init        - (fn [tile-state]) -> initial value for the atom
    :sync?       - boolean, true = included in get-sync-data / make-sync-packet
    :payload-key - keyword (optional), payload key when it differs from :key
                   e.g. :ssid stored under :node-name in the tile state packet
    :coerce      - (fn [v]) -> typed value, applied when writing back from sync data
    :close-reset - value to reset the atom to in on-close")

;; ============================================================================
;; Core Schema Utilities
;; ============================================================================

(defn build-atoms
  "Build a map of {key (atom initial-value)} from a field schema and tile state.

  Args:
  - fields: vector of schema entry maps
  - state:  tile entity state map (passed to each :init fn)

  Returns: map of keyword -> atom"
  [fields state]
  (into {}
        (map (fn [{:keys [key init]}]
               [key (atom (init state))])
             fields)))

(defn get-sync-data
  "Extract sync data from a container based on schema.

  Only includes fields where :sync? is true.

  Args:
  - fields:    vector of schema entry maps
  - container: container map with atom values

  Returns: map of keyword -> deref'd value (ready to send over network)"
  [fields container]
  (into {}
        (for [{:keys [key sync?]} fields
              :when sync?]
          [key @(get container key)])))

(defn reset-atoms!
  "Reset all container atoms to their :close-reset values.

  Intended for use in on-close to clean up synced state.

  Args:
  - fields:    vector of schema entry maps
  - container: container map with atom values

  Side effects: resets every field atom to its :close-reset value"
  [fields container]
  (doseq [{:keys [key close-reset]} fields]
    (when-let [a (get container key)]
      (reset! a close-reset))))

(defn apply-sync-data!
  "Write sync data back into container atoms, applying each field's :coerce fn.

  Args:
  - fields:    vector of schema entry maps
  - container: container map with atom values
  - data:      map of keyword -> raw value (as received from network)

  Side effects: resets each matching atom to the coerced value"
  [fields container data]
  (doseq [{:keys [key coerce]} fields
          :when (contains? data key)]
    (when-let [a (get container key)]
      (reset! a (coerce (get data key))))))

(defn sync-field-mappings
  "Derive the field-mappings vector expected by apply-sync-payload-template!.

  For fields where :payload-key == :key (or :payload-key is absent), emits :key.
  For fields with a different :payload-key, emits [:key :payload-key].
  Only includes fields where :sync? is true.

  Args:
  - fields: vector of schema entry maps

  Returns: vector like [:energy :max-energy [:is-online :enabled] ...]"
  [fields]
  (into []
        (for [{:keys [key payload-key sync?]} fields
              :when sync?]
          (let [pk (or payload-key key)]
            (if (= pk key)
              key
              [key pk])))))

(defn build-sync-packet-fields
  "Build the synced-fields portion of a network packet from a container.

  Only includes fields where :sync? is true. Falls back to :close-reset when
  the container is nil or the atom is missing (tile-only broadcast sources).

  Args:
  - fields:    vector of schema entry maps
  - container: container map with atom values, or nil

  Returns: map of :key -> value (deref'd or :close-reset)"
  [fields container]
  (into {}
        (for [{:keys [key sync? close-reset]} fields
              :when sync?]
          [key (if-let [a (and container (get container key))]
                 @a
                 close-reset)])))

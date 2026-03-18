(ns my-mod.wireless.gui.matrix-sync
  "Cross-platform matrix state synchronization

  Provides platform-agnostic interface for syncing matrix tile state to clients."
  (:require [my-mod.wireless.gui.gui-metadata :as metadata]
            [my-mod.wireless.gui.matrix-fields :as mf]
            [my-mod.wireless.gui.container-schema :as schema]
            [my-mod.wireless.gui.sync-helpers :as sync-helpers]
            [my-mod.platform.position :as pos]))

;; ============================================================================
;; Universal Sync Interface
;; ============================================================================

(defn broadcast-matrix-state
  "Broadcast matrix state to nearby players.

  Delegates to shared sync-helpers implementation.

  Args:
  - world: World object
  - pos: BlockPos object
  - sync-data: Map containing matrix state (must include :gui-id)

  Returns: nil"
  [world pos sync-data]
  (sync-helpers/broadcast-state world pos sync-data "matrix"))

;; ============================================================================
;; Payload Helpers
;; ============================================================================

(defn- matrix-container?
  "Stable type check — uses :container-type set by create-container."
  [source]
  (= (:container-type source) :matrix))

(defn make-sync-packet
  "Create matrix state sync packet payload map from container or tile entity.

  Accepts either a MatrixContainer map or a tile entity directly.
  All synced fields are derived from matrix-fields schema — no manual field
  list to maintain here."
  [source]
  (let [container? (matrix-container? source)
        tile       (if container? (:tile-entity source) source)
        container  (when container? source)
        block-pos  (pos/position-get-block-pos tile)]
    (merge {:gui-id      metadata/gui-wireless-matrix
            :pos-x       (pos/pos-x block-pos)
            :pos-y       (pos/pos-y block-pos)
            :pos-z       (pos/pos-z block-pos)
            :placer-name (or (:placer-name tile) "Unknown")}
           (schema/build-sync-packet-fields mf/matrix-fields container))))

(defn apply-matrix-sync-payload!
  "Apply matrix sync payload to the current client container.

  Field mappings are derived from matrix-fields schema —
  no manual field list to maintain here."
  [payload]
  (sync-helpers/apply-sync-payload-template!
    payload
    (mf/sync-field-mappings)
    "matrix"))

(defn extract-position
  "Extract BlockPos from sync payload.

  Delegates to shared sync-helpers implementation."
  [sync-data world]
  (sync-helpers/extract-position sync-data world))

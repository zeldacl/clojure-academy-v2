(ns cn.li.ac.wireless.gui.node-sync
  "Cross-platform node state synchronization

  Provides platform-agnostic interface for syncing node tile state to clients."
  (:require [cn.li.ac.wireless.gui.gui-metadata :as metadata]
            [cn.li.ac.wireless.gui.node-container :as node-container]
            [cn.li.ac.wireless.gui.container-common :as common]
            [cn.li.ac.wireless.gui.container-schema :as schema]
            [cn.li.ac.wireless.gui.sync-helpers :as sync-helpers]
            [cn.li.mcmod.platform.position :as pos]))

;; ============================================================================
;; Universal Sync Interface
;; ============================================================================

(defn broadcast-node-state
  "Broadcast node state to nearby players.

  Delegates to shared sync-helpers implementation.

  Args:
  - world: World object
  - pos: BlockPos object
  - sync-data: Map containing node state (must include :gui-id)

  Returns: nil"
  [world pos sync-data]
  (sync-helpers/broadcast-state world pos sync-data "node"))

;; ============================================================================
;; Payload Helpers
;; ============================================================================

(defn- node-container?
  "Stable type check — uses :container-type set by create-container."
  [source]
  (= (:container-type source) :node))

(defn- tile-state [tile] (common/get-tile-state tile))

(defn- get-pos
  [tile]
  (when tile
    (try (pos/position-get-block-pos tile) (catch Exception _ nil))))

(defn make-sync-packet
  "Create node state sync packet payload map from container or tile entity.

  Accepts either a NodeContainer map or a tile entity directly.
  All synced fields are derived from node-fields schema — no manual field
  list to maintain here."
  [source]
  (let [container? (node-container? source)
        tile      (if container? (:tile-entity source) source)
        block-pos (get-pos tile)
        state     (tile-state tile)]
    (when block-pos
      (merge {:gui-id metadata/gui-wireless-node
              :pos-x  (pos/pos-x block-pos)
              :pos-y  (pos/pos-y block-pos)
              :pos-z  (pos/pos-z block-pos)}
             (schema/build-sync-packet-payload
              node-container/node-fields
              (when container? source)
              state)))))

(defn apply-node-sync-payload!
  "Apply node sync payload to the current client container.

  Field mappings are derived from node-container/node-fields schema —
  no manual field list to maintain here."
  [payload]
  (sync-helpers/apply-sync-payload-template!
    payload
    (node-container/sync-field-mappings)
    "node"))

(defn extract-position
  "Extract BlockPos from sync payload.

  Delegates to shared sync-helpers implementation."
  [sync-data world]
  (sync-helpers/extract-position sync-data world))

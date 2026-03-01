(ns my-mod.wireless.gui.node-sync
  "Cross-platform node state synchronization
  
  Provides platform-agnostic interface for syncing node tile state to clients."
  (:require [my-mod.util.log :as log]
            [my-mod.wireless.interfaces :as winterfaces]
            [my-mod.wireless.gui.gui-metadata :as metadata]
            [my-mod.wireless.gui.sync-helpers :as sync-helpers]))

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

(defn make-sync-packet
  "Create node state sync packet payload map from container or tile entity
  
  Accepts either a NodeContainer or a tile entity directly"
  [source]
  (let [tile (if (instance? my_mod.wireless.gui.node_container.NodeContainer source)
               (:tile-entity source)
               source)
        pos (:pos tile)]
    {:gui-id metadata/gui-wireless-node
     :pos-x (.getX pos)
     :pos-y (.getY pos)
     :pos-z (.getZ pos)
     :energy (winterfaces/get-energy tile)
     :max-energy (winterfaces/get-max-energy tile)
     :enabled @(:enabled tile)
     :node-name (winterfaces/get-node-name tile)
     :node-type @(:node-type tile)
     :password (winterfaces/get-password tile)
     :charging-in @(:charging-in tile)
     :charging-out @(:charging-out tile)
     :placer-name (:placer-name tile)
     ;; Network capacity fields (added for GUI histogram widgets)
     :capacity (if (instance? my_mod.wireless.gui.node_container.NodeContainer source)
                 @(:capacity source)
                 0)
     :max-capacity (if (instance? my_mod.wireless.gui.node_container.NodeContainer source)
                     @(:max-capacity source)
                     0)}))

(defn apply-node-sync-payload!
  "Apply node sync payload to the current client container.
  
  Uses shared sync-helpers template for consistent behavior."
  [payload]
  (sync-helpers/apply-sync-payload-template!
    payload
    [:energy
     :max-energy
     [:is-online :enabled]
     :node-type
     [:ssid :node-name]
     :password
     :capacity
     :max-capacity]
    "node"))

(defn extract-position
  "Extract BlockPos from sync payload.
  
  Delegates to shared sync-helpers implementation."
  [sync-data world]
  (sync-helpers/extract-position sync-data world))

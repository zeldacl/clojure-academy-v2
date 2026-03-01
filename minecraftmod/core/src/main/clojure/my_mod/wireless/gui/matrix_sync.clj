(ns my-mod.wireless.gui.matrix-sync
  "Cross-platform matrix state synchronization
  
  Provides platform-agnostic interface for syncing matrix tile state to clients."
  (:require [my-mod.util.log :as log]
            [my-mod.wireless.gui.gui-metadata :as metadata]
            [my-mod.wireless.gui.sync-helpers :as sync-helpers]))

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
  "Best-effort structural check for MatrixContainer without class loading."
  [source]
  (and (map? source)
       (contains? source :tile-entity)
       (contains? source :plate-count)
       (contains? source :core-level)))

(defn make-sync-packet
  "Create matrix state sync packet payload map from container or tile entity
  
  Accepts either a MatrixContainer or a tile entity directly"
  [source]
    (let [container? (matrix-container? source)
      tile (if container?
               (:tile-entity source)
               source)
      container (when container?
                    source)
        pos (:pos tile)]
    {:gui-id metadata/gui-wireless-matrix
     :pos-x (.getX pos)
     :pos-y (.getY pos)
     :pos-z (.getZ pos)
     :plate-count (if container @(:plate-count container) 0)
     :placer-name (or (:placer-name tile) "Unknown")
     :is-working (if container @(:is-working container) false)
     :core-level (if container @(:core-level container) 0)
     :capacity (if container @(:capacity container) 0)
     :max-capacity (if container @(:max-capacity container) 0)
     :bandwidth (if container @(:bandwidth container) 0)
     :range (if container @(:range container) 0.0)}))

(defn apply-matrix-sync-payload!
  "Apply matrix sync payload to the current client container.
  
  Uses shared sync-helpers template for consistent behavior."
  [payload]
  (sync-helpers/apply-sync-payload-template!
    payload
    [:plate-count
     :core-level
     :is-working
     :capacity
     :max-capacity
     :bandwidth
     :range]
    "matrix"))

(defn extract-position
  "Extract BlockPos from sync payload.
  
  Delegates to shared sync-helpers implementation."
  [sync-data world]
  (sync-helpers/extract-position sync-data world))


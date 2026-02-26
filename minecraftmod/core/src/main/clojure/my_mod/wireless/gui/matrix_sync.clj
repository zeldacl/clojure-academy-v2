(ns my-mod.wireless.gui.matrix-sync
  "Cross-platform matrix state synchronization
  
  Provides platform-agnostic interface for syncing matrix tile state to clients."
  (:require [my-mod.util.log :as log]
            [my-mod.wireless.gui.gui-metadata :as metadata]
            [my-mod.wireless.gui.registry :as registry]))

;; ============================================================================
;; Universal Sync Interface
;; ============================================================================

(defn broadcast-matrix-state
  "Broadcast matrix state to nearby players
  
  Delegates to platform-specific implementation registered via platform-adapter.
  
  Args:
  - world: World object
  - pos: BlockPos object  
  - sync-data: Map containing matrix state (must include :gui-id)
  
  Returns: nil"
  [world pos sync-data]
  (try
    ;; Get the unified platform broadcast function from platform-adapter
    ;; We use dynamic require to break circular dependency
    (require 'my-mod.gui.platform-adapter)
    (when-let [broadcast-fn @(resolve 'my-mod.gui.platform-adapter/platform-broadcast-fn)]
      (broadcast-fn world pos sync-data))
    (catch Exception e
      (log/debug "Error broadcasting matrix  state:" (.getMessage e)))))

;; ============================================================================
;; Payload Helpers
;; ============================================================================

(defn make-sync-packet
  "Create matrix state sync packet payload map from container or tile entity
  
  Accepts either a MatrixContainer or a tile entity directly"
  [source]
  (let [tile (if (instance? my_mod.wireless.gui.matrix_container.MatrixContainer source)
               (:tile-entity source)
               source)
        container (when (instance? my_mod.wireless.gui.matrix_container.MatrixContainer source)
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
  "Apply matrix sync payload to the current client container"
  [payload]
  (try
    (when-let [container @registry/client-container]
      (when (and (:tile-entity container)
                 (= (:pos-x payload)
                    (try (.getX (.getPos (:tile-entity container)))
                         (catch Exception _ nil))))
        (when (contains? container :plate-count)
          (reset! (:plate-count container) (:plate-count payload)))
        (when (contains? container :core-level)
          (reset! (:core-level container) (:core-level payload)))
        (when (contains? container :is-working)
          (reset! (:is-working container) (:is-working payload)))
        (when (contains? container :capacity)
          (reset! (:capacity container) (:capacity payload)))
        (when (contains? container :max-capacity)
          (reset! (:max-capacity container) (:max-capacity payload)))
        (when (contains? container :bandwidth)
          (reset! (:bandwidth container) (:bandwidth payload)))
        (when (contains? container :range)
          (reset! (:range container) (:range payload)))
        (log/debug "Applied matrix sync payload on client")))
    (catch Exception e
      (log/debug "Failed to apply matrix sync payload:" (.getMessage e)))))

(defn extract-position
  "Extract BlockPos from sync payload"
  [sync-data world]
  (try
    (let [x (:pos-x sync-data)
          y (:pos-y sync-data)
          z (:pos-z sync-data)]
      (net.minecraft.util.math.BlockPos. (int x) (int y) (int z)))
    (catch Exception _ nil)))


(ns my-mod.wireless.gui.matrix-sync
  "Cross-platform matrix state synchronization
  
  Provides platform-agnostic interface for syncing matrix tile state to clients."
  (:require [my-mod.util.log :as log]
            [my-mod.wireless.gui.gui-metadata :as metadata]
            [my-mod.wireless.gui.registry :as registry]))

;; ============================================================================
;; State Synchronization Registry
;; ============================================================================

;; Dynamic dispatch - each platform registers its sync implementation
(defonce ^:private sync-implementations (atom {}))

(defn register-sync-impl!
  "Register a platform-specific sync implementation
  
  Args:
  - platform: keyword (:forge-1.16.5, :forge-1.20.1, :fabric-1.20.1)
  - impl-fn: (fn [world pos sync-data] ...) - broadcasts state to nearby players"
  [platform impl-fn]
  (swap! sync-implementations assoc platform impl-fn)
  (log/info "Registered matrix sync implementation for" platform))

(defn get-sync-impl
  "Get the registered sync implementation for current platform"
  [platform]
  (get @sync-implementations platform))

;; ============================================================================
;; Universal Sync Interface
;; ============================================================================

(defn broadcast-matrix-state
  "Broadcast matrix state to nearby players
  
  Universal interface that delegates to platform-specific implementation.
  
  Args:
  - world: World object
  - pos: BlockPos object  
  - sync-data: Map containing matrix state:
    {:pos-x, :pos-y, :pos-z, :plate-count, :placer-name, :is-working, 
     :core-level, :capacity, :bandwidth, :range}
  
  Returns: nil"
  [world pos sync-data]
  (try
    ;; Try to detect platform from available classes
    (let [platform (cond
                     (try (Class/forName "net.minecraftforge.api.distmarker.Dist") 
                          :forge-1.20.1)
                     (try (Class/forName "net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking")
                          :fabric-1.20.1)
                     :else :unknown)
          impl-fn (get-sync-impl platform)]
      
      (if impl-fn
        (impl-fn world pos sync-data)
        (log/debug "No sync implementation registered for platform" platform)))
    
    (catch Exception e
      (log/debug "Error broadcasting matrix state:" (.getMessage e)))))

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


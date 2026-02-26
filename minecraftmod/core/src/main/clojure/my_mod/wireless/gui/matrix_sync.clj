(ns my-mod.wireless.gui.matrix-sync
  "Cross-platform matrix state synchronization
  
  Provides platform-agnostic interface for syncing matrix tile state to clients."
  (:require [my-mod.util.log :as log]))

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
  "Create matrix state sync packet payload map"
  [tile]
  {:pos-x (.getX (:pos tile))
   :pos-y (.getY (:pos tile))
   :pos-z (.getZ (:pos tile))})

(defn extract-position
  "Extract BlockPos from sync payload"
  [sync-data world]
  (try
    (let [x (:pos-x sync-data)
          y (:pos-y sync-data)
          z (:pos-z sync-data)]
      (net.minecraft.util.math.BlockPos. (int x) (int y) (int z)))
    (catch Exception _ nil)))


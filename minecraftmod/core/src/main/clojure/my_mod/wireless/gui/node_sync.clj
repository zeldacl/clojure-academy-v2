(ns my-mod.wireless.gui.node-sync
  "Cross-platform node state synchronization
  
  Provides platform-agnostic interface for syncing node tile state to clients."
  (:require [my-mod.util.log :as log]
            [my-mod.wireless.interfaces :as winterfaces]))

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
  (log/info "Registered node sync implementation for" platform))

(defn get-sync-impl
  "Get the registered sync implementation for current platform"
  [platform]
  (get @sync-implementations platform))

;; ============================================================================
;; Universal Sync Interface
;; ============================================================================

(defn broadcast-node-state
  "Broadcast node state to nearby players
  
  Universal interface that delegates to platform-specific implementation.
  
  Args:
  - world: World object
  - pos: BlockPos object  
  - sync-data: Map containing node state:
    {:pos-x, :pos-y, :pos-z, :energy, :max-energy, :enabled, 
     :node-name, :node-type, :password, :charging-in, :charging-out,
     :placer-name}
  
  Returns: nil"
  [world pos sync-data]
  (try
    ;; Try to detect platform from available classes
    (let [platform (cond
                     (try (Class/forName "net.minecraftforge.api.distmarker.Dist") 
                          :forge-1.20.1
                          (catch Exception _ nil))
                     (try (Class/forName "net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking")
                          :fabric-1.20.1
                          (catch Exception _ nil))
                     :else :unknown)
          impl-fn (get-sync-impl platform)]
      
      (if impl-fn
        (impl-fn world pos sync-data)
        (log/debug "No sync implementation registered for platform" platform)))
    
    (catch Exception e
      (log/debug "Error broadcasting node state:" (.getMessage e)))))

;; ============================================================================
;; Payload Helpers
;; ============================================================================

(defn make-sync-packet
  "Create node state sync packet payload map from tile entity"
  [tile]
  (let [pos (:pos tile)]
    {:pos-x (.getX pos)
     :pos-y (.getY pos)
     :pos-z (.getZ pos)
     :energy (winterfaces/get-energy tile)
     :max-energy (winterfaces/get-max-energy tile)
     :enabled @(:enabled tile)
     :node-name (winterfaces/get-node-name tile)
     :node-type (:node-type tile)
     :password (winterfaces/get-password tile)
     :charging-in @(:charging-in tile)
     :charging-out @(:charging-out tile)
     :placer-name (:placer-name tile)}))

(defn extract-position
  "Extract BlockPos from sync payload"
  [sync-data world]
  (try
    (let [x (:pos-x sync-data)
          y (:pos-y sync-data)
          z (:pos-z sync-data)]
      ;; Try different BlockPos constructors for cross-version compatibility
      (or (try (eval `(new ~'net.minecraft.core.BlockPos ~x ~y ~z)) (catch Exception _ nil))
          (try (eval `(new ~'net.minecraft.util.math.BlockPos ~x ~y ~z)) (catch Exception _ nil))))
    (catch Exception e
      (log/debug "Failed to extract position:" (.getMessage e))
      nil)))

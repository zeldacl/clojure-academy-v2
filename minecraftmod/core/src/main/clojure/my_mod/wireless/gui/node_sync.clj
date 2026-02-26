(ns my-mod.wireless.gui.node-sync
  "Cross-platform node state synchronization
  
  Provides platform-agnostic interface for syncing node tile state to clients."
  (:require [my-mod.util.log :as log]
            [my-mod.wireless.interfaces :as winterfaces]
            [my-mod.wireless.gui.gui-metadata :as metadata]
            [my-mod.wireless.gui.registry :as registry]))

;; ============================================================================
;; Universal Sync Interface
;; ============================================================================

(defn broadcast-node-state
  "Broadcast node state to nearby players
  
  Delegates to platform-specific implementation registered via platform-adapter.
  
  Args:
  - world: World object
  - pos: BlockPos object  
  - sync-data: Map containing node state (must include :gui-id)
  
  Returns: nil"
  [world pos sync-data]
  (try
    ;; Get the unified platform broadcast function from platform-adapter
    ;; We use dynamic require to break circular dependency
    (require 'my-mod.gui.platform-adapter)
    (when-let [broadcast-fn @(resolve 'my-mod.gui.platform-adapter/platform-broadcast-fn)]
      (broadcast-fn world pos sync-data))
    (catch Exception e
      (log/debug "Error broadcasting node state:" (.getMessage e)))))

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
  "Apply node sync payload to the current client container"
  [payload]
  (try
    (when-let [container @registry/client-container]
      (when (and (:tile-entity container)
                 (= (:pos-x payload)
                    (try (.getX (.getPos (:tile-entity container)))
                         (catch Exception _ nil))))
        (when (contains? container :energy)
          (reset! (:energy container) (:energy payload)))
        (when (contains? container :max-energy)
          (reset! (:max-energy container) (:max-energy payload)))
        (when (contains? container :is-online)
          (reset! (:is-online container) (:enabled payload)))
        (when (contains? container :node-type)
          (reset! (:node-type container) (:node-type payload)))
        (when (contains? container :ssid)
          (reset! (:ssid container) (:node-name payload)))
        (when (contains? container :password)
          (reset! (:password container) (:password payload)))
        (when (contains? container :capacity)
          (reset! (:capacity container) (:capacity payload)))
        (when (contains? container :max-capacity)
          (reset! (:max-capacity container) (:max-capacity payload)))
        (log/debug "Applied node sync payload on client")))
    (catch Exception e
      (log/debug "Failed to apply node sync payload:" (.getMessage e)))))

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

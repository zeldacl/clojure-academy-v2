(ns my-mod.wireless.gui.sync-helpers
  "Shared synchronization utilities for GUI containers.
  
  Provides common functions for broadcasting state and applying sync payloads
  to reduce code duplication between node and matrix sync implementations."
  (:require [my-mod.util.log :as log]
            [my-mod.wireless.gui.registry :as registry]))

;; ============================================================================
;; Universal Broadcast
;; ============================================================================

(defn broadcast-state
  "Generic broadcast function for GUI state synchronization.
  
  Delegates to platform-specific implementation registered via platform-adapter.
  
  Args:
  - world: World object
  - pos: BlockPos object  
  - sync-data: Map containing state (must include :gui-id)
  - log-prefix: String for logging (e.g. 'node', 'matrix')
  
  Returns: nil"
  [world pos sync-data log-prefix]
  (try
    ;; Get the unified platform broadcast function from platform-adapter
    ;; We use dynamic require to break circular dependency
    (require 'my-mod.gui.platform-adapter)
    (when-let [broadcast-fn @(resolve 'my-mod.gui.platform-adapter/platform-broadcast-fn)]
      (broadcast-fn world pos sync-data))
    (catch Exception e
      (log/debug (str "Error broadcasting " log-prefix " state:") (.getMessage e)))))

;; ============================================================================
;; Position Extraction
;; ============================================================================

(defn extract-position
  "Extract BlockPos from sync payload with cross-version compatibility.
  
  Tries multiple BlockPos constructor paths to support different MC versions.
  
  Args:
  - sync-data: Map with :pos-x :pos-y :pos-z keys
  - world: World object (unused but kept for API compatibility)
  
  Returns: BlockPos object or nil"
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

;; ============================================================================
;; Payload Application Helpers
;; ============================================================================

(defn apply-sync-data!
  "Universal sync data application.
  
  Automatically applies data from get-sync-data to container atoms.
  For each key in data map, finds corresponding atom in container and resets it.
  
  Args:
  - container: Container record (with atoms as fields)
  - data: Map of sync data (keys match container field names)
  
  Side effects: Updates all matching atoms in container
  
  Example:
    ;; Instead of:
    ; :sync-apply (fn [container data]
    ;   (reset! (:energy container) (:energy data))
    ;   (reset! (:max-energy container) (:max-energy data)))
    
    ;; Use:
    ; :sync-apply apply-sync-data!"
  [container data]
  (doseq [[k v] data]
    (when-let [atom-ref (get container k)]
      (reset! atom-ref v))))

(defn apply-sync-field!
  "Apply a single sync field to container if it exists.
  
  Args:
  - container: Container map
  - field-key: Keyword for the field
  - payload: Sync payload map
  
  Side effects: Updates container atom if field exists"
  [container field-key payload]
  (when (contains? container field-key)
    (reset! (get container field-key) (get payload field-key))))

(defn apply-payload-fields!
  "Apply multiple fields from payload to container.
  
  Args:
  - container: Container map
  - payload: Sync payload map
  - field-mappings: Vector of [container-key payload-key] or single keyword for same key
  
  Example:
    (apply-payload-fields! container payload
      [:energy :max-energy [:is-online :enabled]])"
  [container payload field-mappings]
  (doseq [mapping field-mappings]
    (let [[container-key payload-key] (if (vector? mapping)
                                        mapping
                                        [mapping mapping])]
      (apply-sync-field! container container-key 
                        (assoc {} container-key (get payload payload-key))))))

(defn apply-sync-payload-template!
  "Template function for applying sync payload to client container.
  
  Checks position match before applying fields.
  
  Args:
  - payload: Sync payload with :pos-x :pos-y :pos-z
  - field-mappings: Field mappings (see apply-payload-fields!)
  - log-prefix: String for logging
  
  Returns: nil"
  [payload field-mappings log-prefix]
  (try
    (when-let [container @registry/client-container]
      (when (and (:tile-entity container)
                 (= (:pos-x payload)
                    (try (.getX (.getPos (:tile-entity container)))
                         (catch Exception _ nil))))
        (apply-payload-fields! container payload field-mappings)
        (log/debug (str "Applied " log-prefix " sync payload on client"))))
    (catch Exception e
      (log/debug (str "Failed to apply " log-prefix " sync payload:") (.getMessage e)))))

;; ============================================================================
;; Position Payload Helpers
;; ============================================================================

(defn make-position-payload
  "Create position payload from tile entity or container.
  
  Args:
  - source: Object with :tile-entity or :pos field
  
  Returns: Map with :pos-x :pos-y :pos-z"
  [source]
  (let [tile (if (contains? source :tile-entity)
               (:tile-entity source)
               source)
        pos (or (:pos tile) 
                (try (.getPos tile) (catch Exception _ nil)))]
    {:pos-x (.getX pos)
     :pos-y (.getY pos)
     :pos-z (.getZ pos)}))

;; ============================================================================
;; Throttling Helpers
;; ============================================================================

(defn with-throttled-sync!
  "Execute a function with throttling based on tick counter.
  
  Common pattern: Execute expensive sync operations every N ticks (default 100 = 5 seconds).
  
  Args:
  - ticker-atom: Atom<int> tracking tick count
  - throttle-ticks: Int, number of ticks between executions (default 100)
  - f: Function to execute when throttle threshold reached
  
  Side effects: Increments ticker, resets to 0 when threshold reached
  
  Example:
    (with-throttled-sync! (:sync-ticker container) 100
      (fn []
        (query-network-capacity! container)))"
  ([ticker-atom f]
   (with-throttled-sync! ticker-atom 100 f))
  ([ticker-atom throttle-ticks f]
   (swap! ticker-atom inc)
   (when (>= @ticker-atom throttle-ticks)
     (reset! ticker-atom 0)
     (try
       (f)
       (catch Exception e
         (log/debug "Error in throttled sync:" (.getMessage e)))))))

(defn query-node-network-capacity!
  "Query network capacity for a node and update container atoms.
  
  Updates:
  - capacity: Current node count in network
  - max-capacity: Matrix max capacity (from matrix tile)
  
  Args:
  - container: NodeContainer with :tile-entity, :capacity, :max-capacity atoms
  - vb: Virtual blocks namespace
  - wd: World data namespace
  - winterfaces: Wireless interfaces namespace"
  [container vb wd winterfaces]
  (try
    (let [tile (:tile-entity container)
          world (:world tile)
          pos (:pos tile)
          node-vblock (vb/create-vnode (.getX pos) (.getY pos) (.getZ pos))
          world-data (wd/get-world-data world)
          network (wd/get-network-by-node world-data node-vblock)]
      (if network
        (do
          (reset! (:capacity container) (count @(:nodes network)))
          ;; Get matrix capacity
          (when-let [matrix-vb (:matrix network)]
            (when-let [matrix (vb/vblock-get matrix-vb world)]
              (reset! (:max-capacity container) 
                     (try (winterfaces/get-capacity matrix) 
                          (catch Exception _ 0))))))
        (do
          (reset! (:capacity container) 0)
          (reset! (:max-capacity container) 0))))
    (catch Exception e
      (reset! (:capacity container) 0)
      (reset! (:max-capacity container) 0))))

(defn query-matrix-network-capacity!
  "Query network capacity for a matrix and update container atoms.
  
  Updates:
  - capacity: Current node count in network
  - max-capacity: Calculated max capacity from stats
  
  Args:
  - container: MatrixContainer with :tile-entity, :capacity, :max-capacity atoms
  - stats: Map with :capacity key (calculated stats)
  - vb: Virtual blocks namespace
  - wd: World data namespace"
  [container stats vb wd]
  (try
    (let [tile (:tile-entity container)
          world (:world tile)
          pos (:pos tile)
          matrix-vblock (vb/create-vmatrix (.getX pos) (.getY pos) (.getZ pos))
          world-data (wd/get-world-data world)
          network (wd/get-network-by-matrix world-data matrix-vblock)]
      (if network
        (do
          ;; Real network capacity (current number of nodes)
          (reset! (:capacity container) (count @(:nodes network)))
          ;; Max capacity from calculated stats
          (reset! (:max-capacity container) (:capacity stats)))
        (do
          (reset! (:capacity container) 0)
          (reset! (:max-capacity container) (:capacity stats)))))
    (catch Exception e
      (log/debug "Error querying matrix network capacity:" (.getMessage e))
      (reset! (:capacity container) 0)
      (reset! (:max-capacity container) 0))))

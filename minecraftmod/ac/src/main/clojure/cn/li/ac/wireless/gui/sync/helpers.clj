(ns cn.li.ac.wireless.gui.sync.helpers
  "Shared synchronization utilities for GUI containers.
  
  Provides common functions for broadcasting state and applying sync payloads
  to reduce code duplication between node and matrix sync implementations."
  (:require [cn.li.mcmod.gui.sync-api :as gui-sync-api]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.ac.wireless.core.capability-resolver :as resolver]
            [cn.li.ac.wireless.data.network-state :as network-state]
            [cn.li.ac.wireless.api :as wireless-api]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.platform.position :as pos]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.gui.container-state :as container-state]))

(defn- owner-session-id
  [owner]
  (or (:server-session-id owner)
      (:client-session-id owner)))

(defn- owner-player-uuid
  [owner]
  (some-> (or (:player-uuid owner)
              (try
                (when-let [player (:player owner)]
                  (uuid/player-uuid player))
                (catch Exception _
                  nil)))
          str))

(defn- resolve-payload-owner
  [payload]
  (let [owner (select-keys payload [:server-session-id
                                    :client-session-id
                                    :player-uuid
                                    :player
                                    :logical-side])]
    (cond-> owner
      (and (nil? (owner-session-id owner))
           runtime-hooks/*client-session-id*)
      (assoc :client-session-id runtime-hooks/*client-session-id*))))

(defn- owner-routing-clues?
  [owner]
  (or (owner-session-id owner)
      (owner-player-uuid owner)))

(defn- owner-container-lookup-ready?
  [owner]
  (and (owner-session-id owner)
       (owner-player-uuid owner)))

(defn- container-owner
  [container]
  (or (:owner container)
      (select-keys container [:server-session-id
                              :client-session-id
                              :player-uuid
                              :player
                              :logical-side
                              :owner])))

(defn- owner-matches?
  [expected-owner container]
  (let [actual-owner (container-owner container)
        expected-session (owner-session-id expected-owner)
        actual-session (owner-session-id actual-owner)
        expected-player (owner-player-uuid expected-owner)
        actual-player (owner-player-uuid actual-owner)]
    (and (or (nil? expected-session)
             (= expected-session actual-session))
         (or (nil? expected-player)
             (= expected-player actual-player)))))

(defn- normalize-container-id
  [container-id]
  (when (some? container-id)
    (try
      (int container-id)
      (catch Exception _
        container-id))))

(defn- container-runtime-id
  [container]
  (normalize-container-id
    (or (:container-id container)
        (:window-id container)
        (:id container))))

(defn- position-match?
  [payload-pos container]
  (try
    (when-let [tile (:tile-entity container)]
      (let [tile-pos (pos/position-get-block-pos tile)]
        (= payload-pos
           [(pos/pos-x tile-pos)
            (pos/pos-y tile-pos)
            (pos/pos-z tile-pos)])))
    (catch Exception _
      false)))

(defn- unique-match
  [pred coll]
  (let [matches (filter pred coll)]
    (when (= 1 (count matches))
      (first matches))))

(defn- find-active-container-for-payload
  "Find the active client container matching a sync payload owner/container/position."
  [payload]
  (let [owner (resolve-payload-owner payload)
        payload-container-id (normalize-container-id (:container-id payload))
        payload-pos [(:pos-x payload) (:pos-y payload) (:pos-z payload)]
        positional? (every? number? payload-pos)
        active-containers (vec (container-state/list-active-containers))
        owner-containers (if (owner-routing-clues? owner)
                           (filterv #(owner-matches? owner %) active-containers)
                           [])]
    (or (when (and payload-container-id (owner-container-lookup-ready? owner))
          (container-state/get-container-by-id owner payload-container-id))
        (when payload-container-id
          (some (fn [container]
                  (when (= payload-container-id (container-runtime-id container))
                    container))
                owner-containers))
        (when positional?
          (some (fn [container]
                  (when (position-match? payload-pos container)
                    container))
                owner-containers))
        (when positional?
          (unique-match #(position-match? payload-pos %) active-containers)))))

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
  [world pos sync-data _log-prefix]
  (gui-sync-api/broadcast-gui-state!* world pos sync-data))

;; ============================================================================
;; Position Extraction
;; ============================================================================

(defn extract-position
  "Extract position from sync payload using platform abstraction.
  
  Args:
  - sync-data: Map with :pos-x :pos-y :pos-z keys
  
  Returns: platform-specific BlockPos object or nil"
  [sync-data]
  (try
    (let [x (:pos-x sync-data)
          y (:pos-y sync-data)
          z (:pos-z sync-data)]
      (when (and (number? x) (number? y) (number? z))
        (pos/create-block-pos x y z)))
    (catch Exception e
      (log/debug "Failed to extract position:"(ex-message e))
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
    (if-let [container (find-active-container-for-payload payload)]
      (do
        (apply-payload-fields! container payload field-mappings)
        (log/debug (str "Applied " log-prefix " sync payload on client")))
      (log/debug (str "Skipped " log-prefix " sync payload without a routed client container")))
    (catch Exception e
      (log/debug (str "Failed to apply " log-prefix " sync payload:")(ex-message e)))))

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
        block-pos (pos/position-get-block-pos tile)]
    {:pos-x (pos/pos-x block-pos)
     :pos-y (pos/pos-y block-pos)
     :pos-z (pos/pos-z block-pos)}))

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
         (log/debug "Error in throttled sync:"(ex-message e)))))))

(defn query-node-network-capacity!
  "Query network capacity for a node and update container atoms.
  
  Updates:
  - capacity: Current node count in network
  - max-capacity: Matrix max capacity (from matrix tile)
  
  Args:
  - container: NodeContainer with :tile-entity, :capacity, :max-capacity atoms"
  [container]
  (try
    (let [tile  (:tile-entity container)
          world (platform-be/be-get-level tile)
          network (wireless-api/get-wireless-net-by-node tile)]
      (if network
        (do
          (reset! (:capacity container) (network-state/get-load network))
          ;; Get matrix capacity
          (when-let [matrix-vb (:matrix network)]
            (when-let [matrix-cap (resolver/resolve-matrix-cap world matrix-vb)]
              (reset! (:max-capacity container)
                      (try
                        (.getMatrixCapacity ^cn.li.acapi.wireless.IWirelessMatrix matrix-cap)
                        (catch Exception _ 0))))))
        (do
          (reset! (:capacity container) 0)
          (reset! (:max-capacity container) 0))))
    (catch Exception _
      (reset! (:capacity container) 0)
      (reset! (:max-capacity container) 0))))

(defn query-matrix-network-capacity!
  "Query network capacity for a matrix and update container atoms.
  
  Updates:
  - capacity: Current node count in network
  - max-capacity: Calculated max capacity from stats
  
  Args:
  - container: MatrixContainer with :tile-entity, :capacity, :max-capacity atoms
  - stats: Map with :capacity key (calculated stats)"
  [container stats]
  (try
    (let [tile  (:tile-entity container)
          network (wireless-api/get-wireless-net-by-matrix tile)
          stats-cap     (long (or (:capacity stats) 0))]
      (if network
        (do
          ;; Real network capacity (current number of nodes)
          (reset! (:capacity container) (network-state/get-load network))
          ;; Max capacity from calculated stats
          (reset! (:max-capacity container) stats-cap))
        (do
          (reset! (:capacity container) 0)
          (reset! (:max-capacity container) stats-cap))))
    (catch Exception e
      (log/debug "Error querying matrix network capacity:"(ex-message e))
      (reset! (:capacity container) 0)
      (reset! (:max-capacity container) 0))))

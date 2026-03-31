(ns cn.li.ac.wireless.world-data
  "World-level wireless network data management

  Manages all wireless networks and node connections for a world:
  - Network registry (SSID-based)
  - Lookup tables for fast queries
  - Persistence (NBT serialization)
  - Tick coordination"
  (:require [cn.li.ac.wireless.virtual-blocks :as vb]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.wireless.world-schema :as ws]
            [cn.li.mcmod.events.world-lifecycle :as world-lifecycle]))

;; ============================================================================
;; WiWorldData Record
;; ============================================================================

(defrecord WiWorldData
  [world           ; World - the world this data belongs to
   net-lookup      ; atom<map> - lookups for WirelessNet
                   ;   vblock -> WirelessNet
                   ;   ssid-string -> WirelessNet
   node-lookup     ; atom<map> - lookups for NodeConn
                   ;   vblock -> NodeConn
   spatial-index   ; atom<map> - chunk-based spatial index
                   ;   [chunk-x chunk-y chunk-z] -> set<vblock>
   networks        ; atom<vector<WirelessNet>> - all networks
   connections])   ; atom<vector<NodeConn>> - all node connections

;; Global registry: world -> WiWorldData
(def ^:private world-data-registry (atom {}))

;; ============================================================================
;; Factory and Access
;; ============================================================================

(defn create-world-data
  "Create new world data for a world"
  [world]
  (->WiWorldData
    world
    (atom {})          ; net-lookup
    (atom {})          ; node-lookup
    (atom {})          ; spatial-index
    (atom [])          ; networks
    (atom [])))        ; connections

(defn get-world-data
  "Get world data for a world, creating if it doesn't exist"
  [world]
  (let [result (atom nil)]
    (swap! world-data-registry
           (fn [registry]
             (if-let [existing (get registry world)]
               (do (reset! result existing)
                   registry)
               (let [new-data (create-world-data world)]
                 (reset! result new-data)
                 (log/info (format "Created WiWorldData for world: %s" world))
                 (assoc registry world new-data)))))
    @result))

(defn get-world-data-non-create
  "Get world data without creating if it doesn't exist"
  [world]
  (get @world-data-registry world))

(defn remove-world-data!
  "Remove world data (called on world unload)"
  [world]
  (swap! world-data-registry dissoc world)
  (log/info (format "Removed WiWorldData for world: %s" world)))

;; ============================================================================
;; Schema
;; ============================================================================

(ws/defworld-from-schemas ws/world-data-schema create-world-data)

;; ============================================================================
;; Spatial Indexing Helpers
;; ============================================================================

(defn- pos->chunk-key
  "Convert world position to chunk key [cx cy cz]"
  [x y z]
  [(quot x 16) (quot y 16) (quot z 16)])

(defn add-to-spatial-index!
  "Add a vblock to the spatial index"
  [world-data vblock]
  (let [chunk-key (pos->chunk-key (:x vblock) (:y vblock) (:z vblock))]
    (swap! (:spatial-index world-data)
           update chunk-key (fnil conj #{}) vblock)))

(defn remove-from-spatial-index!
  "Remove a vblock from the spatial index"
  [world-data vblock]
  (let [chunk-key (pos->chunk-key (:x vblock) (:y vblock) (:z vblock))]
    (swap! (:spatial-index world-data)
           (fn [idx]
             (let [chunk-set (get idx chunk-key)]
               (if chunk-set
                 (let [new-set (disj chunk-set vblock)]
                   (if (empty? new-set)
                     (dissoc idx chunk-key)
                     (assoc idx chunk-key new-set)))
                 idx))))))

(defn get-nearby-chunks
  "Get chunk keys within range of a position.

  Args:
    x, y, z - World position
    range - Search range in blocks

  Returns: Sequence of [cx cy cz] chunk keys"
  [x y z range]
  (let [chunk-range (inc (quot range 16))
        cx (quot x 16)
        cy (quot y 16)
        cz (quot z 16)]
    (for [dx (range (- chunk-range) (inc chunk-range))
          dy (range (- chunk-range) (inc chunk-range))
          dz (range (- chunk-range) (inc chunk-range))]
      [(+ cx dx) (+ cy dy) (+ cz dz)])))

(defn get-vblocks-in-chunks
  "Get all vblocks in the specified chunks.

  Args:
    world-data - WiWorldData instance
    chunk-keys - Sequence of [cx cy cz] chunk keys

  Returns: Set of vblocks"
  [world-data chunk-keys]
  (let [idx @(:spatial-index world-data)]
    (reduce
      (fn [acc chunk-key]
        (if-let [vblocks (get idx chunk-key)]
          (into acc vblocks)
          acc))
      #{}
      chunk-keys)))

;; ============================================================================
;; Network Operations
;; ============================================================================

(defn get-network-by-matrix
  "Get network by matrix vblock"
  [world-data matrix-vblock]
  (get @(:net-lookup world-data) matrix-vblock))

(defn get-network-by-node
  "Get network by node vblock"
  [world-data node-vblock]
  (get @(:net-lookup world-data) node-vblock))

(defn get-network-by-ssid
  "Get network by SSID string"
  [world-data ssid]
  (get @(:net-lookup world-data) ssid))

(defn range-search-networks
  "Search for networks within range of coordinates using spatial index
  Returns collection of networks whose matrix is within range"
  [world-data x y z range max-results]
  (let [range-sq (* range range)
        ;; Use spatial index to get candidate vblocks
        chunk-keys (get-nearby-chunks x y z range)
        candidate-vblocks (get-vblocks-in-chunks world-data chunk-keys)
        ;; Filter to only matrix vblocks and check distance
        net-lookup @(:net-lookup world-data)]
    (->> candidate-vblocks
         (keep (fn [vblock]
                 (when-let [net (get net-lookup vblock)]
                   (when (= vblock (:matrix net))
                     (when (<= (vb/dist-sq-pos vblock x y z) range-sq)
                       net)))))
         (distinct)
         (take max-results))))

;; ============================================================================
;; Node Connection Operations
;; ============================================================================

(defn get-node-connection
  "Get node connection by node/generator/receiver vblock"
  [world-data vblock]
  (get @(:node-lookup world-data) vblock))

(defn ensure-node-connection!
  "Get or create node connection for a node"
  [world-data node-vblock]
  (or (get-node-connection world-data node-vblock)
  (create-node-connection-impl! world-data node-vblock)))

;; ============================================================================
;; Validation and Cleanup
;; ============================================================================

;; ============================================================================
;; NBT Serialization
;; ============================================================================

(defn rebuild-item-lookups!
  "Rebuild lookup tables for a loaded item using a configuration map
  
  Args:
  - world-data: WiWorldData instance
  - item: The loaded item (network or connection)
  - config: Configuration map with:
      :lookup-atom - keyword for the lookup atom in world-data (e.g., :net-lookup)
      :direct-keys - vector of keywords for direct key-value associations
      :collection-keys - vector of keywords for collection-based associations
  
  Example:
    (rebuild-item-lookups! world-data network
      {:lookup-atom :net-lookup
       :direct-keys [:matrix :ssid]
       :collection-keys [:nodes]})"
  [world-data item config]
  (let [lookup-atom (get world-data (:lookup-atom config))]
    ;; Add direct key-value associations
    (doseq [k (:direct-keys config)]
      (when-let [v (get item k)]
        (swap! lookup-atom assoc v item)))
    ;; Add collection-based associations
    (doseq [k (:collection-keys config)]
      (when-let [coll (get item k)]
        (doseq [val @coll]
          (swap! lookup-atom assoc val item))))))

;; Validation/tick/nbt functions are generated by ws/defworld-schema.

;; ============================================================================
;; WorldSavedData Integration
;; ============================================================================

;; Wrapper for platform-specific SavedData / WorldSavedData
(deftype WiSavedDataWrapper
  [^:volatile-mutable wi-data]
  
  ;; IDataManager interface (platform-neutral base)
  Object
  (toString [this]
    (str "WiSavedDataWrapper["
         (if wi-data (str (count @(:networks wi-data)) " networks") "uninitialized")
         "]")))

(defn create-saved-data
  "Create a saved data wrapper for a world"
  [world]
  (let [world-data (create-world-data world)]
    (WiSavedDataWrapper. world-data)))

(defn get-saved-data-world-data
  "Extract WiWorldData from SavedData wrapper"
  [saved-data]
  (when saved-data
    (try
      (.-wi-data saved-data)
      (catch Exception _ nil))))

;; ============================================================================
;; Platform Hooks
;; ============================================================================

;; These functions are called from platform-specific event handlers
(defn on-world-load
  "Called when world loads - restore from saved data"
  [world saved-data]
  (if saved-data
    ;; Restore from saved data
    (let [wi-data (get-saved-data-world-data saved-data)]
      (if wi-data
        (do
          (swap! world-data-registry assoc world wi-data)
          (log/info "Restored WiWorldData for world from save")
          wi-data)
        ;; Saved data corrupted - create fresh
        (let [fresh (create-world-data world)]
          (swap! world-data-registry assoc world fresh)
          fresh)))
    ;; No saved data yet - create new
    (let [fresh (create-world-data world)]
      (swap! world-data-registry assoc world fresh)
      fresh)))

(defn on-world-save
  "Called before world save - prepare data for serialization"
  [world]
  (if-let [wi-data (get-world-data-non-create world)]
    (do
      (when-let [network-validator (resolve 'network-impl-validator)]
        (network-validator wi-data))
      (when-let [connection-validator (resolve 'node-connection-impl-validator)]
        (connection-validator wi-data))
      ;; Return wrapper to be saved
      (let [wrapper (WiSavedDataWrapper. wi-data)]
        (log/info "Prepared WiWorldData for save")
        wrapper))
    ;; No wireless data in this world
    nil))

(defn on-world-unload
  "Called when world unloads - cleanup"
  [world]
  (remove-world-data! world)
  (log/info "Cleaned up WiWorldData for unloaded world"))

(defn get-statistics
  "Get statistics about this world's wireless system"
  [world-data]
  {:networks (count @(:networks world-data))
   :connections (count @(:connections world-data))
   :net-lookups (count @(:net-lookup world-data))
   :node-lookups (count @(:node-lookup world-data))})

(defn print-statistics
  "Print statistics to log"
  [world-data]
  (let [stats (get-statistics world-data)]
    (log/info "=== Wireless System Statistics ===")
    (log/info (format "Networks: %d" (:networks stats)))
    (log/info (format "Connections: %d" (:connections stats)))
    (log/info (format "Network lookups: %d" (:net-lookups stats)))
    (log/info (format "Node lookups: %d" (:node-lookups stats)))))

;; ============================================================================
;; Initialization
;; ============================================================================

(defn init-world-data! []
  (log/info "Registering wireless world data lifecycle handlers...")
  ;; Register world lifecycle handlers with mcmod's event system
  (world-lifecycle/register-world-lifecycle-handler!
    {:on-load   on-world-load
     :on-unload on-world-unload
     :on-save   on-world-save})
  (log/info "World data system initialized"))

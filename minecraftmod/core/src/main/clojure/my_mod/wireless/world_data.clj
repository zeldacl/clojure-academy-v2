(ns my-mod.wireless.world-data
  "World-level wireless network data management
  
  Manages all wireless networks and node connections for a world:
  - Network registry (SSID-based)
  - Lookup tables for fast queries
  - Persistence (NBT serialization)
  - Tick coordination"
  (:require [my-mod.wireless.virtual-blocks :as vb]
            [my-mod.util.log :as log]))

;; ============================================================================
;; WiWorldData Record
;; ============================================================================

(defrecord WiWorldData
  [world           ; World - the world this data belongs to
   net-lookup      ; atom<map> - lookups for WirelessNet
                   ;   vblock → WirelessNet
                   ;   ssid-string → WirelessNet
   node-lookup     ; atom<map> - lookups for NodeConn
                   ;   vblock → NodeConn
   networks        ; atom<vector<WirelessNet>> - all networks
   connections])   ; atom<vector<NodeConn>> - all node connections

;; Global registry: world → WiWorldData
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
    (atom [])          ; networks
    (atom [])))        ; connections

(defn get-world-data
  "Get world data for a world, creating if it doesn't exist"
  [world]
  (if-let [data (get @world-data-registry world)]
    data
    (let [new-data (create-world-data world)]
      (swap! world-data-registry assoc world new-data)
      (log/info (format "Created WiWorldData for world: %s" world))
      new-data)))

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
;; Network Operations
;; ============================================================================

(defn create-network!
  "Create a new wireless network
  Returns true if successful, false if SSID already exists"
  [world-data matrix-vblock ssid password]
  (let [net-lookup @(:net-lookup world-data)]
    (if (contains? net-lookup ssid)
      (do
        (log/info (format "Cannot create network: SSID '%s' already exists" ssid))
        false)
      (let [;; Create network (forward declaration, will be defined in network.clj)
            network (my-mod.wireless.network/create-wireless-net
                      world-data matrix-vblock ssid password)]
        ;; Add to networks list
        (swap! (:networks world-data) conj network)
        ;; Add to lookup tables
        (swap! (:net-lookup world-data) assoc matrix-vblock network)
        (swap! (:net-lookup world-data) assoc ssid network)
        (log/info (format "Created network: SSID='%s'" ssid))
        true))))

(defn destroy-network!
  "Destroy a wireless network"
  [world-data network]
  (let [ssid (:ssid network)
        matrix (:matrix network)]
    ;; Mark as disposed
    (reset! (:disposed network) true)
    ;; Remove from lookup
    (swap! (:net-lookup world-data) dissoc matrix)
    (swap! (:net-lookup world-data) dissoc ssid)
    ;; Remove all node lookups
    (doseq [node @(:nodes network)]
      (swap! (:net-lookup world-data) dissoc node))
    ;; Remove from networks list
    (swap! (:networks world-data)
           (fn [nets] (filterv #(not= % network) nets)))
    (log/info (format "Destroyed network: SSID='%s'" ssid))))

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
  "Search for networks within range of coordinates
  Returns collection of networks whose matrix is within range"
  [world-data x y z range max-results]
  (let [range-sq (* range range)
        all-networks @(:networks world-data)]
    (->> all-networks
         (filter (fn [net]
                   (let [matrix (:matrix net)]
                     (<= (vb/dist-sq-pos matrix x y z) range-sq))))
         (take max-results))))

;; ============================================================================
;; Node Connection Operations
;; ============================================================================

(defn create-node-connection!
  "Create a new node connection
  Returns the created NodeConn"
  [world-data node-vblock]
  (let [;; Create connection (forward declaration)
        conn (my-mod.wireless.node-connection/create-node-conn
               world-data node-vblock)]
    ;; Add to connections list
    (swap! (:connections world-data) conj conn)
    ;; Add to lookup
    (swap! (:node-lookup world-data) assoc node-vblock conn)
    (log/info (format "Created node connection: %s" (vb/vblock-to-string node-vblock)))
    conn))

(defn destroy-node-connection!
  "Destroy a node connection"
  [world-data conn]
  (let [node (:node conn)]
    ;; Mark as disposed
    (reset! (:disposed conn) true)
    ;; Remove from lookup
    (swap! (:node-lookup world-data) dissoc node)
    ;; Remove all generator/receiver lookups
    (doseq [gen @(:generators conn)]
      (swap! (:node-lookup world-data) dissoc gen))
    (doseq [rec @(:receivers conn)]
      (swap! (:node-lookup world-data) dissoc rec))
    ;; Remove from connections list
    (swap! (:connections world-data)
           (fn [conns] (filterv #(not= % conn) conns)))
    (log/info (format "Destroyed node connection: %s" (vb/vblock-to-string node)))))

(defn get-node-connection
  "Get node connection by node/generator/receiver vblock"
  [world-data vblock]
  (get @(:node-lookup world-data) vblock))

(defn ensure-node-connection!
  "Get or create node connection for a node"
  [world-data node-vblock]
  (or (get-node-connection world-data node-vblock)
      (create-node-connection! world-data node-vblock)))

;; ============================================================================
;; Validation and Cleanup
;; ============================================================================

(defn validate-networks!
  "Validate all networks, removing invalid ones
  Returns number of networks removed"
  [world-data]
  (let [world (:world world-data)
        networks-before (count @(:networks world-data))]
    ;; Filter out disposed or invalid networks
    (swap! (:networks world-data)
           (fn [nets]
             (filterv (fn [net]
                        (and (not @(:disposed net))
                             ;; Check if matrix still exists
                             (let [matrix (:matrix net)]
                               (or (not (vb/is-chunk-loaded? matrix world))
                                   (some? (vb/vblock-get matrix world))))))
                      nets)))
    ;; Cleanup disposed networks
    (doseq [net @(:networks world-data)]
      (when @(:disposed net)
        (destroy-network! world-data net)))
    (let [networks-after (count @(:networks world-data))
          removed (- networks-before networks-after)]
      (when (> removed 0)
        (log/info (format "Removed %d invalid networks" removed)))
      removed)))

(defn validate-connections!
  "Validate all node connections, removing invalid ones
  Returns number of connections removed"
  [world-data]
  (let [world (:world world-data)
        conns-before (count @(:connections world-data))]
    ;; Filter out disposed or invalid connections
    (swap! (:connections world-data)
           (fn [conns]
             (filterv (fn [conn]
                        (and (not @(:disposed conn))
                             ;; Check if node still exists
                             (let [node (:node conn)]
                               (or (not (vb/is-chunk-loaded? node world))
                                   (some? (vb/vblock-get node world))))))
                      conns)))
    ;; Cleanup disposed connections
    (doseq [conn @(:connections world-data)]
      (when @(:disposed conn)
        (destroy-node-connection! world-data conn)))
    (let [conns-after (count @(:connections world-data))
          removed (- conns-before conns-after)]
      (when (> removed 0)
        (log/info (format "Removed %d invalid connections" removed)))
      removed)))

;; ============================================================================
;; Tick System
;; ============================================================================

(defn tick-world-data!
  "Tick all networks and connections in this world data"
  [world-data]
  ;; Validate first
  (validate-networks! world-data)
  (validate-connections! world-data)
  
  ;; Tick all networks
  (doseq [net @(:networks world-data)]
    (when-not @(:disposed net)
      (my-mod.wireless.network/tick-wireless-net! net)))
  
  ;; Tick all connections
  (doseq [conn @(:connections world-data)]
    (when-not @(:disposed conn)
      (my-mod.wireless.node-connection/tick-node-conn! conn))))

;; ============================================================================
;; NBT Serialization
;; ============================================================================

(defn world-data-to-nbt
  "Serialize world data to NBT"
  [world-data]
  (let [nbt (net.minecraft.nbt.NBTTagCompound.)
        
        ;; Save networks
        networks-list (net.minecraft.nbt.NBTTagList.)
        _ (doseq [net @(:networks world-data)]
            (when-not @(:disposed net)
              (.appendTag networks-list
                         (my-mod.wireless.network/network-to-nbt net))))
        _ (.setTag nbt "networks" networks-list)
        
        ;; Save connections
        conns-list (net.minecraft.nbt.NBTTagList.)
        _ (doseq [conn @(:connections world-data)]
            (when-not @(:disposed conn)
              (.appendTag conns-list
                         (my-mod.wireless.node-connection/conn-to-nbt conn))))
        _ (.setTag nbt "connections" conns-list)]
    nbt))

(defn world-data-from-nbt
  "Deserialize world data from NBT"
  [world nbt]
  (let [world-data (create-world-data world)
        
        ;; Load networks
        networks-list (.getTagList nbt "networks" 10) ; 10 = compound tag
        _ (dotimes [i (.tagCount networks-list)]
            (let [net-nbt (.getCompoundTagAt networks-list i)
                  net (my-mod.wireless.network/network-from-nbt world-data net-nbt)]
              (swap! (:networks world-data) conj net)
              ;; Rebuild lookup tables
              (swap! (:net-lookup world-data) assoc (:matrix net) net)
              (swap! (:net-lookup world-data) assoc (:ssid net) net)
              (doseq [node @(:nodes net)]
                (swap! (:net-lookup world-data) assoc node net))))
        
        ;; Load connections
        conns-list (.getTagList nbt "connections" 10)
        _ (dotimes [i (.tagCount conns-list)]
            (let [conn-nbt (.getCompoundTagAt conns-list i)
                  conn (my-mod.wireless.node-connection/conn-from-nbt world-data conn-nbt)]
              (swap! (:connections world-data) conj conn)
              ;; Rebuild lookup tables
              (swap! (:node-lookup world-data) assoc (:node conn) conn)
              (doseq [gen @(:generators conn)]
                (swap! (:node-lookup world-data) assoc gen conn))
              (doseq [rec @(:receivers conn)]
                (swap! (:node-lookup world-data) assoc rec conn))))]
    
    (log/info (format "Loaded %d networks and %d connections from NBT"
                      (count @(:networks world-data))
                      (count @(:connections world-data))))
    world-data))

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
         "]"))
  
  ;; Marker to indicate this is our wireless data
  (markDirty [this]
    ;; Called when data changed
    true))

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
      (validate-networks! wi-data)
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
  (log/info "World data system initialized"))

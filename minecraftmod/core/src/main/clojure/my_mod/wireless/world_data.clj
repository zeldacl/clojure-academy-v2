(ns my-mod.wireless.world-data
  "World-level wireless network data management
  
  Manages all wireless networks and node connections for a world:
  - Network registry (SSID-based)
  - Lookup tables for fast queries
  - Persistence (NBT serialization)
  - Tick coordination"
  (:require [my-mod.wireless.virtual-blocks :as vb]
            [my-mod.util.log :as log]
            [my-mod.nbt.dsl :as nbt]))

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
;; Collection Ops Macro
;; ============================================================================

(defmacro defworld-collection-ops
  "Define create/destroy functions for a world-data collection using a spec.
  
  Required keys in spec:
    :create-args    - vector of symbols for create fn args (excluding world-data)
    :create-expr    - form that creates the item
    :list-atom      - keyword for the collection atom in world-data
    :lookup-atom    - keyword for the lookup atom in world-data
  Optional keys:
    :direct-keys     - vector of keywords for direct lookup keys on the item
    :collection-keys - vector of keywords for collection fields on the item
    :key-sources     - vector of maps {:values-expr <form> :when-expr <form>}
    :unique-keys    - vector of maps {:label "..." :value-expr <form> :value-fn <fn>}
    :return-on-unique-fail - return value when uniqueness check fails
    :create-return  - value to return on successful create (default: item)
    :log-create     - format string for create log
    :log-create-key-expr - form for create log key
    :log-destroy    - format string for destroy log
    :log-destroy-key-expr - form for destroy log key
    :log-key-fn     - function to format keys in logs
    :log-create-fail - format string for uniqueness failure log
  
  Example:
    (defworld-collection-ops node-connection
      {:create-args [node-vblock]
       :create-expr (my-mod.wireless.node-connection/create-node-conn world-data node-vblock)
       :list-atom :connections
       :lookup-atom :node-lookup
       :direct-keys [:node]
       :collection-keys [:generators :receivers]
       :log-create "Created node connection: %s"
       :log-create-key-expr node-vblock
       :log-destroy "Destroyed node connection: %s"
       :log-destroy-key-expr (:node item)
       :log-key-fn vb/vblock-to-string})"
  [base-name spec]
  (let [create-name (symbol (str "create-" (name base-name) "!"))
        destroy-name (symbol (str "destroy-" (name base-name) "!"))
        {:keys [create-args create-expr list-atom lookup-atom direct-keys collection-keys key-sources
                unique-keys return-on-unique-fail create-return log-create log-create-key-expr
                log-destroy log-destroy-key-expr log-key-fn log-create-fail]} spec
        create-args (or create-args [])
        direct-keys (or direct-keys [])
        collection-keys (or collection-keys [])
        unique-keys (or unique-keys [])
        key-sources (or key-sources [])
        log-key-fn (or log-key-fn 'identity)
        create-return (or create-return 'item)
        direct-key-forms (mapv (fn [k]
                                 `(when-let [~'value (get ~'item ~k)]
                                    (swap! ~'lookup-atom assoc ~'value ~'item)))
                               direct-keys)
        collection-key-forms (mapv (fn [k]
                                     `(when-let [~'coll (get ~'item ~k)]
                                        (doseq [~'val @~'coll]
                                          (swap! ~'lookup-atom assoc ~'val ~'item))))
                                   collection-keys)
        key-source-forms (mapv (fn [entry]
                                 (let [{:keys [values-expr when-expr]} entry
                                       when-expr (or when-expr true)]
                                   `(when ~when-expr
                                      (doseq [~'val ~values-expr]
                                        (swap! ~'lookup-atom assoc ~'val ~'item)))))
                               key-sources)
        direct-key-remove-forms (mapv (fn [k]
                                        `(when-let [~'value (get ~'item ~k)]
                                           (swap! ~'lookup-atom dissoc ~'value)))
                                      direct-keys)
        collection-key-remove-forms (mapv (fn [k]
                                            `(when-let [~'coll (get ~'item ~k)]
                                               (doseq [~'val @~'coll]
                                                 (swap! ~'lookup-atom dissoc ~'val))))
                                          collection-keys)
        key-source-remove-forms (mapv (fn [entry]
                                        (let [{:keys [values-expr when-expr]} entry
                                              when-expr (or when-expr true)]
                                          `(when ~when-expr
                                             (doseq [~'val ~values-expr]
                                               (swap! ~'lookup-atom dissoc ~'val)))))
                                      key-sources)
        unique-check-forms (mapv (fn [entry]
                       (let [{:keys [label value-expr value-fn]} entry
                         value-fn (or value-fn 'identity)
                         label (or label "key")]
                       `(let [~'value ~value-expr
                          ~'formatted (~value-fn ~'value)]
                        (when (contains? @~'lookup-atom ~'value)
                          {:label ~label :value ~'formatted}))))
                     unique-keys)]
    `(do
       (defn ~create-name
         ~(str "Create a new " (name base-name) "\n"
               "Returns the created item")
         [~'world-data ~@create-args]
         (let [~'list-atom (get ~'world-data ~list-atom)
               ~'lookup-atom (get ~'world-data ~lookup-atom)
               ~'conflict ~(if (seq unique-check-forms)
                             `(or ~@unique-check-forms)
                             nil)]
           (if ~'conflict
             (do
               (when ~log-create-fail
                 (log/info (format ~log-create-fail (:label ~'conflict) (:value ~'conflict))))
               ~return-on-unique-fail)
             (let [~'item ~create-expr]
               (swap! ~'list-atom conj ~'item)
               ~@direct-key-forms
               ~@collection-key-forms
               ~@key-source-forms
               (when ~log-create
                 (log/info (format ~log-create (~log-key-fn ~log-create-key-expr))))
               ~create-return))))
       (defn ~destroy-name
         ~(str "Destroy a " (name base-name))
         [~'world-data ~'item]
         (let [~'list-atom (get ~'world-data ~list-atom)
               ~'lookup-atom (get ~'world-data ~lookup-atom)]
           (reset! (:disposed ~'item) true)
           ~@direct-key-remove-forms
           ~@collection-key-remove-forms
           ~@key-source-remove-forms
           (swap! ~'list-atom (fn [~'items] (filterv #(not= % ~'item) ~'items)))
           (when ~log-destroy
             (log/info (format ~log-destroy (~log-key-fn ~log-destroy-key-expr)))))))))

;; ============================================================================
;; Network Operations
;; ============================================================================

(defworld-collection-ops network
  {:create-args [matrix-vblock ssid password]
   :create-expr (my-mod.wireless.network/create-wireless-net
                  world-data matrix-vblock ssid password)
   :list-atom :networks
   :lookup-atom :net-lookup
   :direct-keys [:matrix :ssid]
  :key-sources [{:values-expr @(:nodes item)}]
   :unique-keys [{:label "SSID" :value-expr ssid :value-fn identity}
                 {:label "matrix" :value-expr matrix-vblock :value-fn vb/vblock-to-string}]
   :return-on-unique-fail false
   :create-return true
   :log-create "Created network: SSID='%s'"
   :log-create-key-expr ssid
   :log-destroy "Destroyed network: SSID='%s'"
   :log-destroy-key-expr (:ssid item)
   :log-key-fn identity
   :log-create-fail "Cannot create network: %s '%s' already exists"})

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

(defworld-collection-ops node-connection
  {:create-args [node-vblock]
   :create-expr (my-mod.wireless.node-connection/create-node-conn
                  world-data node-vblock)
   :list-atom :connections
   :lookup-atom :node-lookup
   :direct-keys [:node]
   :collection-keys [:generators :receivers]
   :log-create "Created node connection: %s"
   :log-create-key-expr node-vblock
   :log-destroy "Destroyed node connection: %s"
   :log-destroy-key-expr (:node item)
   :log-key-fn vb/vblock-to-string})

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

(nbt/defworldnbt world-data
  :create create-world-data
  :lists [{:tag "networks"
           :atom :networks
           :to-nbt my-mod.wireless.network/network-to-nbt
           :from-nbt my-mod.wireless.network/network-from-nbt
           :skip? (fn [net] @(:disposed net))
           :rebuild {:lookup-atom :net-lookup
                     :direct-keys [:matrix :ssid]
                     :collection-keys [:nodes]}}
          {:tag "connections"
           :atom :connections
           :to-nbt my-mod.wireless.node-connection/conn-to-nbt
           :from-nbt my-mod.wireless.node-connection/conn-from-nbt
           :skip? (fn [conn] @(:disposed conn))
           :rebuild {:lookup-atom :node-lookup
                     :direct-keys [:node]
                     :collection-keys [:generators :receivers]}}]
  :after-read
  [(log/info (format "Loaded %d networks and %d connections from NBT"
                     (count @(:networks world-data))
                     (count @(:connections world-data))))])

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

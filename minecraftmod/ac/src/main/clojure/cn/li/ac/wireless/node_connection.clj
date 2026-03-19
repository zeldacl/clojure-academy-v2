(ns my-mod.wireless.node-connection
  "Node Connection management
  
  Manages connections between a node and generators/receivers:
  - Generator energy collection
  - Receiver energy distribution
  - Capacity management
  - Range validation"
  (:require [my-mod.wireless.virtual-blocks :as vb]
            [my-mod.util.log :as log]))

;; ============================================================================
;; NodeConn Record
;; ============================================================================

(defrecord NodeConn
  [world-data         ; WiWorldData - parent world data
   node               ; VBlock - center node
   receivers          ; atom<vector<VBlock>> - receiver list
   generators         ; atom<vector<VBlock>> - generator list
   to-remove-receivers ; atom<vector<VBlock>> - receivers to remove
   to-remove-generators ; atom<vector<VBlock>> - generators to remove
   disposed])         ; atom<boolean> - disposed flag

;; ============================================================================
;; Factory
;; ============================================================================

(defn create-node-conn
  "Create a new node connection"
  [world-data node-vblock]
  (->NodeConn
    world-data
    node-vblock
    (atom [])        ; receivers
    (atom [])        ; generators
    (atom [])        ; to-remove-receivers
    (atom [])        ; to-remove-generators
    (atom false)))   ; disposed

;; ============================================================================
;; Accessors
;; ============================================================================

(defn get-node
  "Get the node TileEntity"
  [conn]
  (vb/vblock-get (:node conn) (:world (:world-data conn))))

(defn is-disposed? [conn] @(:disposed conn))

(defn get-load
  "Get total load (receivers + generators)"
  [conn]
  (+ (count @(:receivers conn))
     (count @(:generators conn))))

(defn get-capacity
  "Get node capacity"
  [conn]
  (if-let [node (get-node conn)]
    (.getCapacity ^ cn.li.ac.api.wireless.IWirelessNode node)
    Integer/MAX_VALUE))

(declare remove-receiver! remove-generator!)

(defn- find-existing-node-connection
  "Lookup existing node connection via world-data namespace at runtime.
  Uses requiring-resolve to avoid compile-time circular dependency."
  [world-data vblock]
  (if-let [lookup-fn (requiring-resolve 'my-mod.wireless.world-data/get-node-connection)]
    (lookup-fn world-data vblock)
    nil))

;; ============================================================================
;; Range Checking
;; ============================================================================

(defn- check-range
  "Check if vblock is within node range"
  [conn vblock]
  (if-let [node (get-node conn)]
    (let [range (.getRange ^ cn.li.ac.api.wireless.IWirelessNode node)
          dist-sq (vb/dist-sq vblock (:node conn))]
      (<= dist-sq (* range range)))
    false))

;; ============================================================================
;; Receiver Management
;; ============================================================================

(defn add-receiver!
  "Add a receiver to this node connection
  Returns true if successful"
  [conn receiver-vb]
  (cond
    (>= (get-load conn) (get-capacity conn))
    (do
      (log/info "Receiver add failed: node at capacity")
      false)

    (not (check-range conn receiver-vb))
    (do
      (log/info "Receiver add failed: out of range")
      false)

    :else
    (do
      ;; Remove from old connection if exists
      (let [old-conn (find-existing-node-connection (:world-data conn) receiver-vb)]
        (when old-conn
          (remove-receiver! old-conn receiver-vb)))

      ;; Add to this connection
      (swap! (:receivers conn) conj receiver-vb)

      ;; Update lookup
      (swap! (:node-lookup (:world-data conn))
             assoc receiver-vb conn)

      (log/info (format "Added receiver %s to node %s"
                        (vb/vblock-to-string receiver-vb)
                        (vb/vblock-to-string (:node conn))))
      true)))

(defn remove-receiver!
  "Mark receiver for removal"
  [conn receiver-vb]
  (swap! (:to-remove-receivers conn) conj receiver-vb))

;; ============================================================================
;; Generator Management
;; ============================================================================

(defn add-generator!
  "Add a generator to this node connection
  Returns true if successful"
  [conn generator-vb]
  (cond
    (>= (get-load conn) (get-capacity conn))
    (do
      (log/info "Generator add failed: node at capacity")
      false)

    (not (check-range conn generator-vb))
    (do
      (log/info "Generator add failed: out of range")
      false)

    :else
    (do
      ;; Remove from old connection if exists
      (let [old-conn (find-existing-node-connection (:world-data conn) generator-vb)]
        (when old-conn
          (remove-generator! old-conn generator-vb)))

      ;; Add to this connection
      (swap! (:generators conn) conj generator-vb)

      ;; Update lookup
      (swap! (:node-lookup (:world-data conn))
             assoc generator-vb conn)

      (log/info (format "Added generator %s to node %s"
            (vb/vblock-to-string generator-vb)
            (vb/vblock-to-string (:node conn))))
      true)))

(defn remove-generator!
  "Mark generator for removal"
  [conn generator-vb]
  (swap! (:to-remove-generators conn) conj generator-vb))

;; ============================================================================
;; Cleanup
;; ============================================================================

(defn- cleanup-removed!
  "Remove all marked receivers and generators"
  [conn]
  (let [to-remove-recs @(:to-remove-receivers conn)
        to-remove-gens @(:to-remove-generators conn)]
    
    ;; Remove receivers
    (when (seq to-remove-recs)
      (swap! (:receivers conn)
             (fn [recs]
               (filterv #(not (some (partial vb/vblock-equals? %) to-remove-recs))
                        recs)))
      (doseq [rec to-remove-recs]
        (swap! (:node-lookup (:world-data conn)) dissoc rec))
      (reset! (:to-remove-receivers conn) []))
    
    ;; Remove generators
    (when (seq to-remove-gens)
      (swap! (:generators conn)
             (fn [gens]
               (filterv #(not (some (partial vb/vblock-equals? %) to-remove-gens))
                        gens)))
      (doseq [gen to-remove-gens]
        (swap! (:node-lookup (:world-data conn)) dissoc gen))
      (reset! (:to-remove-generators conn) []))))

;; ============================================================================
;; Validation
;; ============================================================================

(defn validate!
  "Validate connection integrity
  Returns true if valid, false if should be disposed"
  [conn]
  (let [world (:world (:world-data conn))
        node-vb (:node conn)]
    (when (and (not @(:disposed conn))
               (vb/is-chunk-loaded? node-vb world))
      (when (and (nil? (vb/vblock-get node-vb world))
                 (zero? (count @(:generators conn)))
                 (zero? (count @(:receivers conn))))
        ;; Node destroyed and no users
        (reset! (:disposed conn) true)))
    (not @(:disposed conn))))

;; ============================================================================
;; Energy Transfer
;; ============================================================================

(defn- transfer-from-generators!
  "Collect energy from generators to node"
  [conn node bandwidth]
  (let [world (:world (:world-data conn))
        generators-shuffled (shuffle @(:generators conn))]
    (loop [gens-remaining generators-shuffled
           transfer-left bandwidth]
      (when (and (seq gens-remaining) (> transfer-left 0))
        (let [gen-vb (first gens-remaining)]
          (if (vb/is-chunk-loaded? gen-vb world)
            (if-let [gen (vb/vblock-get gen-vb world)]
              (let [;; Get energy from generator
                    energy-available (.getEnergy ^ cn.li.ac.api.wireless.IWirelessGenerator gen)
                    to-collect (min energy-available transfer-left)

                    ;; Charge to node
                    node-max (.getMaxEnergy ^ cn.li.ac.api.wireless.IWirelessNode node)
                    node-current (.getEnergy ^ cn.li.ac.api.wireless.IWirelessNode node)
                    node-space (- node-max node-current)
                    actual-transfer (min to-collect node-space)]

                ;; Transfer energy
                (.setEnergy ^ cn.li.ac.api.wireless.IWirelessGenerator gen (- energy-available actual-transfer))
                (.setEnergy ^ cn.li.ac.api.wireless.IWirelessNode node (+ node-current actual-transfer))
                
                (recur (rest gens-remaining)
                       (- transfer-left actual-transfer)))
              
              ;; Generator destroyed
              (do (remove-generator! conn gen-vb)
                  (recur (rest gens-remaining) transfer-left)))
            
            ;; Chunk not loaded
            (recur (rest gens-remaining) transfer-left)))))))

(defn- transfer-to-receivers!
  "Distribute energy from node to receivers"
  [conn node bandwidth]
  (let [world (:world (:world-data conn))
        receivers-shuffled (shuffle @(:receivers conn))]
    (loop [recs-remaining receivers-shuffled
           transfer-left bandwidth]
      (when (and (seq recs-remaining) (> transfer-left 0))
        (let [rec-vb (first recs-remaining)]
          (if (vb/is-chunk-loaded? rec-vb world)
            (if-let [rec (vb/vblock-get rec-vb world)]
              (let [;; Pull from node
                    node-current (.getEnergy ^ cn.li.ac.api.wireless.IWirelessNode node)
                    to-send (min node-current transfer-left)

                    ;; Inject to receiver
                    leftover (.injectEnergy ^ cn.li.ac.api.wireless.IWirelessReceiver rec to-send)
                    actual-transfer (- to-send leftover)]

                ;; Update node energy
                (.setEnergy ^ cn.li.ac.api.wireless.IWirelessNode node (- node-current actual-transfer))
                
                (recur (rest recs-remaining)
                       (- transfer-left actual-transfer)))
              
              ;; Receiver destroyed
              (do (remove-receiver! conn rec-vb)
                  (recur (rest recs-remaining) transfer-left)))
            
            ;; Chunk not loaded
            (recur (rest recs-remaining) transfer-left)))))))

;; ============================================================================
;; Tick System
;; ============================================================================

(defn tick-node-conn!
  "Tick the node connection"
  [conn]
  (when-not @(:disposed conn)
    ;; Validate
    (when (validate! conn)
      (let [world (:world (:world-data conn))
            node-vb (:node conn)]
        (when (vb/is-chunk-loaded? node-vb world)
          (when-let [node (vb/vblock-get node-vb world)]
            (let [bandwidth (.getBandwidth ^ cn.li.ac.api.wireless.IWirelessNode node)]
              ;; Collect from generators
              (transfer-from-generators! conn node bandwidth)
              
              ;; Distribute to receivers
              (transfer-to-receivers! conn node bandwidth))))
        
        ;; Cleanup removed users
        (cleanup-removed! conn)))))

;; ============================================================================
;; Disposal
;; ============================================================================

(defn dispose!
  "Dispose the connection"
  [conn]
  (reset! (:disposed conn) true)
  (log/info (format "Node connection %s disposed"
                    (vb/vblock-to-string (:node conn)))))

;; ============================================================================
;; Connection Schema Definition
;; ============================================================================

(def conn-schema
  '{:collection-name :node-connection
    :atom-name :connections
    :lookup-atom :node-lookup
    :factory {:fn create-node-conn
              :args [world-data node]}
    :nbt-fields [{:name :node :type :vblock :nbt-key "node" :factory-arg true}
                 {:name :receivers :type :vblock-list :nbt-key "receivers" :atom? true}
                 {:name :generators :type :vblock-list :nbt-key "generators" :atom? true}]
    :create {:args [node-vblock]
             :expr '(my-mod.wireless.node-connection/create-node-conn world-data node-vblock)
             :return 'item
             :return-on-unique-fail false
             :log-create "Created node connection: %s"
             :log-create-key-expr 'node-vblock}
    :destroy {:log-destroy "Destroyed node connection: %s"
              :log-destroy-key-expr '(:node item)}
    :direct-keys [:node]
    :collection-keys [:generators :receivers]
    :log-key-fn 'my-mod.wireless.virtual-blocks/vblock-to-string
    :validator {:vblock-key :node
                :log-label "connections"}
    :tick {:fn 'my-mod.wireless.node-connection/tick-node-conn!}
    :nbt {:tag "connections"
          :atom :connections
          :to-nbt 'my-mod.wireless.node-connection/conn-to-nbt
          :from-nbt 'my-mod.wireless.node-connection/conn-from-nbt
          :skip? '(fn [conn] @(:disposed conn))
          :rebuild {:lookup-atom :node-lookup
                    :direct-keys [:node]
                    :collection-keys [:generators :receivers]}}})

;; ============================================================================
;; NBT Serialization
;; ============================================================================

(my-mod.wireless.world-schema/defnbt-handlers-from-schema conn-schema)

;; ============================================================================
;; Debug
;; ============================================================================

(defn print-conn-info
  "Print connection information"
  [conn]
  (log/info (format "=== NodeConn: %s ===" (vb/vblock-to-string (:node conn))))
  (log/info (format "  Load: %d/%d" (get-load conn) (get-capacity conn)))
  (log/info (format "  Generators: %d" (count @(:generators conn))))
  (log/info (format "  Receivers: %d" (count @(:receivers conn))))
  (log/info (format "  Disposed: %s" @(:disposed conn))))

;; ============================================================================
;; Initialization
;; ============================================================================

(defn init-node-connection! []
  (log/info "Node connection system initialized"))

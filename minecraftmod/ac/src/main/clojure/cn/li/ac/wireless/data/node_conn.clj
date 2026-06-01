(ns cn.li.ac.wireless.data.node-conn
  "Node Connection management
  
  Manages connections between a node and generators/receivers:
  - Generator energy collection
  - Receiver energy distribution
  - Capacity management
  - Range validation"
  (:require [clojure.string :as str]
            [cn.li.ac.wireless.core.vblock :as vb]
            [cn.li.ac.wireless.core.capability-resolver :as resolver]
            [cn.li.ac.wireless.data.vblock-codec :as vblock-codec]
            [cn.li.ac.wireless.data.world-registry :as world-registry]
            [cn.li.mcmod.platform.nbt :as nbt]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; NodeConn Record
;; ============================================================================

(defrecord NodeConn
  [world-data         ; WiWorldData - parent world data
   node               ; VBlock - center node
  state])            ; atom<{:receivers :generators :disposed}>

;; ============================================================================
;; Factory
;; ============================================================================

(defn create-node-conn
  "Create a new node connection"
  [world-data node-vblock]
  (->NodeConn
    world-data
    node-vblock
    (atom {:receivers []
           :generators []
           :disposed false})))

;; ============================================================================
;; Accessors
;; ============================================================================

(defn get-node
  "Get the node TileEntity"
  [conn]
  (resolver/resolve-node-cap (:world (:world-data conn)) (:node conn)))

(defn get-receivers [conn]
  (vec (:receivers @(:state conn))))

(defn get-generators [conn]
  (vec (:generators @(:state conn))))

(defn is-disposed? [conn]
  (boolean (:disposed @(:state conn))))

(defn set-disposed!
  [conn value]
  (swap! (:state conn) assoc :disposed (boolean value))
  (boolean value))

(defn get-load
  "Get total load (receivers + generators)"
  [conn]
    (+ (count (get-receivers conn))
      (count (get-generators conn))))

(defn get-capacity
  "Get node capacity"
  [conn]
  (if-let [node (get-node conn)]
    (.getCapacity ^ cn.li.acapi.wireless.IWirelessNode node)
    Integer/MAX_VALUE))

(declare remove-receiver! remove-generator!)

(defn- find-existing-node-connection
  "Lookup existing node connection directly from world-data lookup table."
  [world-data vblock]
  (get (world-registry/node-lookup world-data) vblock))

;; ============================================================================
;; Range Checking
;; ============================================================================

(defn- check-range
  "Check if vblock is within node range"
  [conn vblock]
  (if-let [node (get-node conn)]
    (let [range (.getRange ^ cn.li.acapi.wireless.IWirelessNode node)
          dist-sq (vb/dist-sq vblock (:node conn))]
      (<= dist-sq (* range range)))
    false))

;; ============================================================================
;; Device Management (Unified)
;; ============================================================================

(defn- capacity-available?
  [conn]
  (< (get-load conn) (get-capacity conn)))

(defn- resolve-device-context
  [_conn device-type]
  {:device-key (case device-type
                 :receiver :receivers
                 :generator :generators)
   :remove-fn (case device-type
                :receiver remove-receiver!
                :generator remove-generator!)
   :device-name (name device-type)})

(defn- remove-device-from-old-connection!
  [conn device-vb remove-fn]
  (let [old-conn (find-existing-node-connection (:world-data conn) device-vb)]
    (when old-conn
      (remove-fn old-conn device-vb))))

(defn- attach-device!
  [conn device-vb device-key device-name]
  (world-registry/transact!
    (:world-data conn)
    (fn [_]
      (swap! (:state conn) update device-key conj device-vb)
      (world-registry/update-state-value! (:world-data conn) :node-lookup assoc device-vb conn)
      (log/info (format "Added %s %s to node %s"
                        device-name
                        (vb/vblock-to-string device-vb)
                        (vb/vblock-to-string (:node conn))))
      true)))

(defn- remove-device!
  [conn device-vb device-key device-name]
  (world-registry/transact!
    (:world-data conn)
    (fn [_]
      (let [devices (get @(:state conn) device-key)
            removed? (boolean (some #(vb/vblock-equals? % device-vb) devices))]
        (when removed?
          (swap! (:state conn) update device-key
                 (fn [items]
                   (filterv #(not (vb/vblock-equals? % device-vb)) items)))
          (world-registry/update-state-value! (:world-data conn) :node-lookup dissoc device-vb)
          (log/info (format "Removed %s %s from node %s"
                            device-name
                            (vb/vblock-to-string device-vb)
                            (vb/vblock-to-string (:node conn)))))
        removed?))))

(defn- add-device!
  "Generic function to add a device (receiver or generator) to node connection
  Returns true if successful"
  [conn device-vb device-type]
  (let [{:keys [device-key remove-fn device-name]} (resolve-device-context conn device-type)]
    (cond
      (not (capacity-available? conn))
      (do
        (log/info (format "%s add failed: node at capacity" (str/capitalize device-name)))
        false)

      (not (check-range conn device-vb))
      (do
        (log/info (format "%s add failed: out of range" (str/capitalize device-name)))
        false)

      :else
      (do
        (remove-device-from-old-connection! conn device-vb remove-fn)
        (attach-device! conn device-vb device-key device-name)))))

;; ============================================================================
;; Receiver Management
;; ============================================================================

(defn add-receiver!
  "Add a receiver to this node connection
  Returns true if successful"
  [conn receiver-vb]
  (add-device! conn receiver-vb :receiver))

(defn remove-receiver!
  "Remove receiver from this node connection immediately."
  [conn receiver-vb]
  (remove-device! conn receiver-vb :receivers "receiver"))

;; ============================================================================
;; Generator Management
;; ============================================================================

(defn add-generator!
  "Add a generator to this node connection
  Returns true if successful"
  [conn generator-vb]
  (add-device! conn generator-vb :generator))

(defn remove-generator!
  "Remove generator from this node connection immediately."
  [conn generator-vb]
  (remove-device! conn generator-vb :generators "generator"))

;; ============================================================================
;; Validation
;; ============================================================================

(defn validate!
  "Validate connection integrity
  Returns true if valid, false if should be disposed"
  [conn]
  (let [world (:world (:world-data conn))
        node-vb (:node conn)]
    (when (and (not (is-disposed? conn))
               (vb/is-chunk-loaded? node-vb world))
      (let [node-exists? (some? (vb/vblock-get node-vb world))
            empty? (and (zero? (count (get-generators conn)))
                        (zero? (count (get-receivers conn))))]
        (when (or (not node-exists?)
                  empty?)
          (set-disposed! conn true))))
    (not (is-disposed? conn))))

;; ============================================================================
;; Energy Transfer
;; ============================================================================

(defn- transfer-from-generators!
  "Collect energy from generators to node"
  [conn node bandwidth]
  (when (> bandwidth 0)
    (let [generators (get-generators conn)]
      (when (seq generators)
        (let [world (:world (:world-data conn))
              generators-shuffled (shuffle generators)]
          (loop [gens-remaining generators-shuffled
                 transfer-left bandwidth]
            (when (and (seq gens-remaining) (> transfer-left 0))
              (let [gen-vb (first gens-remaining)]
                (if (vb/is-chunk-loaded? gen-vb world)
                  (if-let [gen (resolver/resolve-generator-cap world gen-vb)]
                    (let [gen-cap ^cn.li.acapi.wireless.IWirelessGenerator gen
                          ;; Match AC NodeConn semantics:
                          ;; request by node transfer-left, generator bandwidth, and node free space.
                          node-max (.getMaxEnergy ^cn.li.acapi.wireless.IWirelessNode node)
                          node-current (.getEnergy ^cn.li.acapi.wireless.IWirelessNode node)
                          node-space (- node-max node-current)
                          required (min transfer-left
                                        (double (.getGeneratorBandwidth gen-cap))
                                        node-space)
                          provided (double (.getProvidedEnergy gen-cap required))
                          actual-transfer (if (> provided required)
                                            (do
                                              (log/warn "Energy input overflow for generator" (str gen-cap)
                                                        "provided=" provided "required=" required)
                                              required)
                                            provided)]
                      (.setEnergy ^cn.li.acapi.wireless.IWirelessNode node (+ node-current actual-transfer))

                      (recur (rest gens-remaining)
                             (- transfer-left actual-transfer)))

                    ;; Generator destroyed
                    (do (remove-generator! conn gen-vb)
                        (recur (rest gens-remaining) transfer-left)))

                  ;; Chunk not loaded
                  (recur (rest gens-remaining) transfer-left))))))))))

(defn- transfer-to-receivers!
  "Distribute energy from node to receivers"
  [conn node bandwidth]
  (when (> bandwidth 0)
    (let [receivers (get-receivers conn)]
      (when (seq receivers)
        (let [world (:world (:world-data conn))
              receivers-shuffled (shuffle receivers)]
          (loop [recs-remaining receivers-shuffled
                 transfer-left bandwidth]
            (when (and (seq recs-remaining) (> transfer-left 0))
              (let [rec-vb (first recs-remaining)]
                (if (vb/is-chunk-loaded? rec-vb world)
                  (if-let [rec (resolver/resolve-receiver-cap world rec-vb)]
                    (let [rec-cap ^cn.li.acapi.wireless.IWirelessReceiver rec
                          ;; Match AC NodeConn semantics:
                          ;; bounded by node energy, node transfer-left, receiver bandwidth and requirement.
                          node-current (.getEnergy ^cn.li.acapi.wireless.IWirelessNode node)
                          give0 (min node-current
                                     transfer-left
                                     (double (.getReceiverBandwidth rec-cap)))
                          give (min give0 (double (.getRequiredEnergy rec-cap)))
                          leftover (double (.injectEnergy rec-cap give))
                          actual-transfer (- give leftover)]

                      ;; Update node energy
                      (.setEnergy ^cn.li.acapi.wireless.IWirelessNode node (- node-current actual-transfer))

                      (recur (rest recs-remaining)
                             (- transfer-left actual-transfer)))

                    ;; Receiver destroyed
                    (do (remove-receiver! conn rec-vb)
                        (recur (rest recs-remaining) transfer-left)))

                  ;; Chunk not loaded
                  (recur (rest recs-remaining) transfer-left))))))))))

;; ============================================================================
;; Tick System
;; ============================================================================

(defn tick-node-conn!
  "Tick the node connection"
  [conn]
  (when-not (is-disposed? conn)
    ;; Validate
    (when (validate! conn)
      (let [world (:world (:world-data conn))
            node-vb (:node conn)]
        (when (vb/is-chunk-loaded? node-vb world)
          (when-let [node (resolver/resolve-node-cap world node-vb)]
            (let [bandwidth (.getBandwidth ^ cn.li.acapi.wireless.IWirelessNode node)]
              ;; Collect from generators
              (transfer-from-generators! conn node bandwidth)
              
              ;; Distribute to receivers
                (transfer-to-receivers! conn node bandwidth))))))))

;; ============================================================================
;; Disposal
;; ============================================================================

(defn dispose!
  "Dispose the connection"
  [conn]
  (set-disposed! conn true)
  (log/info (format "Node connection %s disposed"
                    (vb/vblock-to-string (:node conn)))))

;; ============================================================================
;; NBT Serialization
;; ============================================================================

(defn node-connection-to-nbt
  "Serialize node connection to NBT."
  [conn]
  (let [nbt-compound (nbt/create-nbt-compound)
        receivers-list (nbt/create-nbt-list)
        generators-list (nbt/create-nbt-list)
        world (:world (:world-data conn))]
    (nbt/nbt-set-tag! nbt-compound "node" (vblock-codec/vblock-to-nbt (:node conn)))
    (doseq [receiver-vb (get-receivers conn)]
      (when (or (not (vb/is-chunk-loaded? receiver-vb world))
                (resolver/resolve-receiver-cap world receiver-vb))
        (nbt/nbt-append! receivers-list (vblock-codec/vblock-to-nbt receiver-vb))))
    (doseq [generator-vb (get-generators conn)]
      (when (or (not (vb/is-chunk-loaded? generator-vb world))
                (resolver/resolve-generator-cap world generator-vb))
        (nbt/nbt-append! generators-list (vblock-codec/vblock-to-nbt generator-vb))))
    (nbt/nbt-set-tag! nbt-compound "receivers" receivers-list)
    (nbt/nbt-set-tag! nbt-compound "generators" generators-list)
    nbt-compound))

(defn node-connection-from-nbt
  "Deserialize node connection from NBT."
  [world-data nbt-compound]
  (let [node-vb (vb/vblock-from-nbt (nbt/nbt-get-compound nbt-compound "node"))
        receivers-list (nbt/nbt-get-list nbt-compound "receivers")
        generators-list (nbt/nbt-get-list nbt-compound "generators")
        receivers-size (nbt/nbt-list-size receivers-list)
        generators-size (nbt/nbt-list-size generators-list)
        receivers (vec (for [i (range receivers-size)]
                         (vb/vblock-from-nbt (nbt/nbt-list-get-compound receivers-list i))))
        generators (vec (for [i (range generators-size)]
                          (vb/vblock-from-nbt (nbt/nbt-list-get-compound generators-list i))))
        conn (create-node-conn world-data node-vb)]
      (swap! (:state conn) assoc :receivers receivers :generators generators)
    conn))

;; ============================================================================
;; Debug
;; ============================================================================

(defn print-conn-info
  "Print connection information"
  [conn]
  (log/info (format "=== NodeConn: %s ===" (vb/vblock-to-string (:node conn))))
  (log/info (format "  Load: %d/%d" (get-load conn) (get-capacity conn)))
  (log/info (format "  Generators: %d" (count (get-generators conn))))
  (log/info (format "  Receivers: %d" (count (get-receivers conn))))
  (log/info (format "  Disposed: %s" (is-disposed? conn))))

;; ============================================================================
;; Initialization
;; ============================================================================

(defn init-node-connection! []
  (log/info "Node connection system initialized"))

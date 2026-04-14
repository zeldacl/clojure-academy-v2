(ns cn.li.ac.wireless.data.node-conn
  "Node Connection management
  
  Manages connections between a node and generators/receivers:
  - Generator energy collection
  - Receiver energy distribution
  - Capacity management
  - Range validation"
  (:require [clojure.string :as str]
            [cn.li.ac.wireless.core.vblock :as vb]
            [cn.li.mcmod.platform.nbt :as nbt]
            [cn.li.mcmod.util.log :as log]))

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
    (.getCapacity ^ cn.li.acapi.wireless.IWirelessNode node)
    Integer/MAX_VALUE))

(declare remove-receiver! remove-generator!)

(defn- find-existing-node-connection
  "Lookup existing node connection directly from world-data lookup table."
  [world-data vblock]
  (get @(:node-lookup world-data) vblock))

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
  [conn device-type]
  {:device-atom (case device-type
                  :receiver (:receivers conn)
                  :generator (:generators conn))
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
  [conn device-vb device-atom device-name]
  (swap! device-atom conj device-vb)
  (swap! (:node-lookup (:world-data conn)) assoc device-vb conn)
  (log/info (format "Added %s %s to node %s"
                    device-name
                    (vb/vblock-to-string device-vb)
                    (vb/vblock-to-string (:node conn))))
  true)

(defn- add-device!
  "Generic function to add a device (receiver or generator) to node connection
  Returns true if successful"
  [conn device-vb device-type]
  (let [{:keys [device-atom remove-fn device-name]} (resolve-device-context conn device-type)]
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
        (attach-device! conn device-vb device-atom device-name)))))

;; ============================================================================
;; Receiver Management
;; ============================================================================

(defn add-receiver!
  "Add a receiver to this node connection
  Returns true if successful"
  [conn receiver-vb]
  (add-device! conn receiver-vb :receiver))

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
  (add-device! conn generator-vb :generator))

(defn remove-generator!
  "Mark generator for removal"
  [conn generator-vb]
  (swap! (:to-remove-generators conn) conj generator-vb))

;; ============================================================================
;; Cleanup
;; ============================================================================

(defn- cleanup-removed-devices!
  "Remove devices marked for removal from collection and lookup"
  [conn device-atom to-remove-atom]
  (let [to-remove @to-remove-atom]
    (when (seq to-remove)
      (swap! device-atom
             (fn [devices]
               (filterv #(not (some (partial vb/vblock-equals? %) to-remove))
                        devices)))
      (doseq [device to-remove]
        (swap! (:node-lookup (:world-data conn)) dissoc device))
      (reset! to-remove-atom []))))

(defn- cleanup-removed!
  "Remove all marked receivers and generators"
  [conn]
  (cleanup-removed-devices! conn (:receivers conn) (:to-remove-receivers conn))
  (cleanup-removed-devices! conn (:generators conn) (:to-remove-generators conn)))

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
  (when (> bandwidth 0)
    (let [generators @(:generators conn)]
      (when (seq generators)
        (let [world (:world (:world-data conn))
              generators-shuffled (shuffle generators)]
          (loop [gens-remaining generators-shuffled
                 transfer-left bandwidth]
            (when (and (seq gens-remaining) (> transfer-left 0))
              (let [gen-vb (first gens-remaining)]
                (if (vb/is-chunk-loaded? gen-vb world)
                  (if-let [gen (vb/vblock-get gen-vb world)]
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
    (let [receivers @(:receivers conn)]
      (when (seq receivers)
        (let [world (:world (:world-data conn))
              receivers-shuffled (shuffle receivers)]
          (loop [recs-remaining receivers-shuffled
                 transfer-left bandwidth]
            (when (and (seq recs-remaining) (> transfer-left 0))
              (let [rec-vb (first recs-remaining)]
                (if (vb/is-chunk-loaded? rec-vb world)
                  (if-let [rec (vb/vblock-get rec-vb world)]
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
  (when-not @(:disposed conn)
    ;; Validate
    (when (validate! conn)
      (let [world (:world (:world-data conn))
            node-vb (:node conn)]
        (when (vb/is-chunk-loaded? node-vb world)
          (when-let [node (vb/vblock-get node-vb world)]
            (let [bandwidth (.getBandwidth ^ cn.li.acapi.wireless.IWirelessNode node)]
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
;; NBT Serialization
;; ============================================================================

(defn node-connection-to-nbt
  "Serialize node connection to NBT."
  [conn]
  (let [nbt-compound (nbt/create-nbt-compound)
        receivers-list (nbt/create-nbt-list)
        generators-list (nbt/create-nbt-list)
        world (:world (:world-data conn))]
    (nbt/nbt-set-tag! nbt-compound "node" (vb/vblock-to-nbt (:node conn)))
    (doseq [receiver-vb @(:receivers conn)]
      (when (or (not (vb/is-chunk-loaded? receiver-vb world))
                (vb/vblock-get receiver-vb world))
        (nbt/nbt-append! receivers-list (vb/vblock-to-nbt receiver-vb))))
    (doseq [generator-vb @(:generators conn)]
      (when (or (not (vb/is-chunk-loaded? generator-vb world))
                (vb/vblock-get generator-vb world))
        (nbt/nbt-append! generators-list (vb/vblock-to-nbt generator-vb))))
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
    (reset! (:receivers conn) receivers)
    (reset! (:generators conn) generators)
    conn))

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

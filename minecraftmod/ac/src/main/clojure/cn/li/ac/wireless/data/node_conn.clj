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
            [cn.li.ac.wireless.data.entity-commit :as entity-commit]
            [cn.li.ac.wireless.data.vblock-codec :as vblock-codec]
            [cn.li.ac.wireless.data.world-registry :as world-registry]
            [cn.li.ac.wireless.domain.topology :as topology]
            [cn.li.ac.wireless.domain.transfer :as transfer]
            [cn.li.ac.wireless.runtime.effects :as effects]
            [cn.li.mcmod.platform.nbt :as nbt]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.acapi.wireless
            IWirelessGenerator
            IWirelessNode
            IWirelessReceiver]))

;; ============================================================================
;; NodeConn Record
;; ============================================================================

(defrecord NodeConn
  [world-data
   node
   state])

(defn- default-state
  []
  {:receivers []
   :generators []
   :disposed false})

(defn create-node-conn
  "Create a new node connection"
  [world-data node-vblock]
  (->NodeConn world-data node-vblock (default-state)))

;; ============================================================================
;; Accessors
;; ============================================================================

(defn- state-value [conn key] (get (:state conn) key))

(defn- with-state [conn state] (assoc conn :state state))

(defn- update-state [conn f] (with-state conn (f (:state conn))))

(defn- commit!
  [conn updated]
  (entity-commit/commit-connection! (:world-data conn) conn updated))

(defn get-node
  "Get the node TileEntity"
  [conn]
  (resolver/resolve-node-cap (:world (:world-data conn)) (:node conn)))

(defn get-receivers [conn]
  (vec (or (state-value conn :receivers) [])))

(defn get-generators [conn]
  (vec (or (state-value conn :generators) [])))

(defn is-disposed? [conn]
  (boolean (state-value conn :disposed)))

(defn set-disposed!
  [conn value]
  (let [updated (update-state conn #(assoc % :disposed (boolean value)))]
    (commit! conn updated)))

(defn get-load
  "Get total load (receivers + generators)"
  [conn]
  (+ (count (get-receivers conn))
     (count (get-generators conn))))

(defn get-capacity
  "Get node capacity"
  [conn]
  (if-let [node (get-node conn)]
    (.getCapacity ^IWirelessNode node)
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
    (let [range (.getRange ^IWirelessNode node)
          dist-sq (vb/dist-sq vblock (:node conn))]
      (<= dist-sq (* range range)))
    false))

;; ============================================================================
;; Device Management (Unified)
;; ============================================================================

(defn- capacity-available? [conn]
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
      (let [world-data (:world-data conn)
            conn (entity-commit/resolve-connection world-data conn)
            ;; Clear :disposed flag — a previously empty connection may have been
            ;; disposed by validate!, but adding a device revives it.
            conn* (update-state conn #(-> %
                                          (update device-key conj device-vb)
                                          (assoc :disposed false)))]
        (entity-commit/replace-connection-in-state! world-data conn conn*)
        (world-registry/update-state!
          world-data
          (fn [state]
            (-> state
                (topology/link-connection-device conn* device-vb)
                (assoc-in [:node-lookup (:node conn*)] conn*))))
        (log/info (format "Added %s %s to node %s"
                          device-name
                          (vb/vblock-to-string device-vb)
                          (vb/vblock-to-string (:node conn*))))
        true))))

(defn- remove-device!
  [conn device-vb device-key device-name]
  (let [world-data (:world-data conn)
        conn (entity-commit/resolve-connection world-data conn)]
    (world-registry/transact!
      world-data
      (fn [_]
        (let [conn (entity-commit/resolve-connection world-data conn)
              devices (state-value conn device-key)
            removed? (boolean (some #(vb/vblock-equals? % device-vb) devices))]
        (when removed?
          (let [conn* (update-state
                        conn
                        (fn [state]
                          (update state device-key
                                  (fn [items]
                                    (filterv #(not (vb/vblock-equals? % device-vb)) items)))))]
            (entity-commit/replace-connection-in-state! world-data conn conn*)
            (world-registry/update-state!
              world-data
              (fn [state]
                (-> state
                    (topology/unlink-connection-device device-vb)
                    (assoc-in [:node-lookup (:node conn*)] conn*))))
            (log/info (format "Removed %s %s from node %s"
                              device-name
                              (vb/vblock-to-string device-vb)
                              (vb/vblock-to-string (:node conn))))
            true))
        removed?)))))

(defn- add-device!
  "Generic function to add a device (receiver or generator) to node connection
  Returns true if successful"
  [conn device-vb device-type]
  (let [conn (entity-commit/resolve-connection (:world-data conn) conn)
        {:keys [device-key remove-fn device-name]} (resolve-device-context conn device-type)]
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
  (let [conn (entity-commit/resolve-connection (:world-data conn) conn)
        world (:world (:world-data conn))
        node-vb (:node conn)
        conn (if (and (not (is-disposed? conn))
                      (vb/is-chunk-loaded? node-vb world))
               (let [node-exists? (some? (vb/vblock-get node-vb world))
                     empty? (and (zero? (count (get-generators conn)))
                                 (zero? (count (get-receivers conn))))]
                 (if (or (not node-exists?) empty?)
                   (set-disposed! conn true)
                   conn))
               conn)]
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
                 transfer-left (double bandwidth)]
            (when (and (seq gens-remaining) (pos? transfer-left))
              (let [gen-vb (first gens-remaining)]
                (if (vb/is-chunk-loaded? gen-vb world)
                  (if-let [gen-cap (resolver/resolve-generator-cap world gen-vb)]
                    (let [node-max (double (.getMaxEnergy ^IWirelessNode node))
                          node-energy (double (.getEnergy ^IWirelessNode node))
                          gen-bandwidth (double (.getGeneratorBandwidth ^IWirelessGenerator gen-cap))
                          node-space (- node-max node-energy)
                          required (min transfer-left gen-bandwidth node-space)
                          provided (double (.getProvidedEnergy ^IWirelessGenerator gen-cap required))
                          step (transfer/collect-from-generator-step
                                transfer-left node-energy node-max gen-bandwidth provided)]
                      (if step
                        (do
                          (effects/apply-generator-collect-step!
                            node (assoc step :provided provided) gen-cap)
                          (recur (rest gens-remaining) (double (:transfer-left step))))
                        (recur (rest gens-remaining) transfer-left)))

                    ;; gen-cap nil — skip, don't remove.
                    ;; Capability may be resolvable next tick.
                    (do
                      (log/debug (format "[transfer-from-generators!] skipping %s — gen-cap nil"
                                         (vb/vblock-to-string gen-vb)))
                      (recur (rest gens-remaining) transfer-left)))

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
                 transfer-left (double bandwidth)]
            (when (and (seq recs-remaining) (pos? transfer-left))
              (let [rec-vb (first recs-remaining)]
                (if (vb/is-chunk-loaded? rec-vb world)
                  (if-let [rec-cap (resolver/resolve-receiver-cap world rec-vb)]
                    (let [node-energy (double (.getEnergy ^IWirelessNode node))
                          step (transfer/distribute-to-receiver-step
                                transfer-left
                                node-energy
                                (double (.getReceiverBandwidth ^IWirelessReceiver rec-cap))
                                (double (.getRequiredEnergy ^IWirelessReceiver rec-cap)))]
                      (if step
                        (let [actual (effects/apply-receiver-distribute-step! node step rec-cap)]
                          (recur (rest recs-remaining) (- transfer-left actual)))
                        (recur (rest recs-remaining) transfer-left)))

                    ;; rec-cap is nil — tile exists but doesn't expose IWirelessReceiver.
                    ;; Don't remove; the receiver may be a multiblock controller whose
                    ;; capability is resolved through tile-logic rather than instanceof.
                    ;; Just skip this tick and try again next time.
                    (do
                      (log/debug (format "[transfer-to-receivers!] skipping %s — rec-cap nil (tile exists but no IWirelessReceiver resolved)"
                                         (vb/vblock-to-string rec-vb)))
                      (recur (rest recs-remaining) transfer-left)))

                  ;; chunk not loaded — receiver block may be in unloaded area.
                  ;; Don't remove; it will be re-validated when chunk loads again.
                  (recur (rest recs-remaining) transfer-left))))))))))

;; ============================================================================
;; Tick System
;; ============================================================================

(defn tick-node-conn!
  "Tick the node connection"
  [conn]
  (let [conn (entity-commit/resolve-connection (:world-data conn) conn)]
    (when-not (is-disposed? conn)
      (when (validate! conn)
        (let [conn (entity-commit/resolve-connection (:world-data conn) conn)
              world (:world (:world-data conn))
              node-vb (:node conn)]
          (when (vb/is-chunk-loaded? node-vb world)
            (when-let [node (resolver/resolve-node-cap world node-vb)]
              (let [bandwidth (.getBandwidth ^IWirelessNode node)]
                (transfer-from-generators! conn node bandwidth)
                (transfer-to-receivers! conn node bandwidth)))))))))

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
                          (vb/vblock-from-nbt (nbt/nbt-list-get-compound generators-list i))))]
    (-> (create-node-conn world-data node-vb)
        (assoc :state {:receivers receivers :generators generators :disposed false}))))

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


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
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.wireless.config :as wcfg])
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

(defn- stale-device-cooldown-ticks
  "A device must be stale for this many consecutive ticks before removal.
  Reads from wireless config (default 20 ticks = 1 second), enough for multiblock
  structure validation to complete."
  []
  (try (wcfg/stale-device-cooldown-ticks)
       (catch Exception _ 20)))

(defn- default-state
  []
  {:receivers []
   :generators []
   :stale-counters {}   ; vblock-str -> consecutive stale tick count
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
  "Validate connection integrity — count stale devices and remove only after
  cooldown. Dispose empty or orphaned connections.
  Returns true if valid, false if should be disposed."
  [conn]
  (let [conn (entity-commit/resolve-connection (:world-data conn) conn)
        world (:world (:world-data conn))]
    (if-not world
      (do (log/warn "[wireless] Validate: world is nil for connection, skipping validation")
          (not (is-disposed? conn)))
      (let [node-vb (:node conn)
            ;; --- per-device stale detection ---
            stale-receiver? (fn [rec-vb]
                              (and (vb/is-chunk-loaded? rec-vb world)
                                   (nil? (resolver/resolve-receiver-cap world rec-vb))))
            stale-generator? (fn [gen-vb]
                               (and (vb/is-chunk-loaded? gen-vb world)
                                    (nil? (resolver/resolve-generator-cap world gen-vb))))
            stale-recs (filterv stale-receiver? (get-receivers conn))
            stale-gens (filterv stale-generator? (get-generators conn))
            ;; --- tick counters: increment stale, reset healthy, purge orphans ---
            counters   (or (:stale-counters (:state conn)) {})
            ;; increment
            counters   (reduce (fn [c rec-vb]
                                 (let [k (vb/vblock-to-string rec-vb)]
                                   (update c k (fnil inc 0))))
                               counters stale-recs)
            counters   (reduce (fn [c gen-vb]
                                 (let [k (vb/vblock-to-string gen-vb)]
                                   (update c k (fnil inc 0))))
                               counters stale-gens)
            ;; reset: healthy devices go back to 0 (eager filterv per REVIEW.md §4)
            counters   (reduce (fn [c rec-vb]
                                 (let [k (vb/vblock-to-string rec-vb)]
                                   (dissoc c k)))
                               counters (filterv (complement stale-receiver?) (get-receivers conn)))
            counters   (reduce (fn [c gen-vb]
                                 (let [k (vb/vblock-to-string gen-vb)]
                                   (dissoc c k)))
                               counters (filterv (complement stale-generator?) (get-generators conn)))
            ;; --- prune orphaned counters for devices no longer in the lists ---
            active-keys (into #{} (map vb/vblock-to-string) (concat (get-receivers conn) (get-generators conn)))
            counters   (reduce-kv (fn [c k _] (if (active-keys k) c (dissoc c k)))
                                 counters counters)
            ;; --- commit updated counters (wrapped in transact! for atomicity) ---
            conn (world-registry/transact!
                   (:world-data conn)
                   (fn [_]
                     (let [updated (update-state conn #(assoc % :stale-counters counters))]
                       (entity-commit/replace-connection-in-state!
                         (:world-data conn) conn updated)
                       updated)))]
        ;; --- remove devices that exceeded cooldown ---
        (doseq [r stale-recs
                :let [k (vb/vblock-to-string r)]
                :when (>= (get counters k 0) (stale-device-cooldown-ticks))]
          (log/warn "[wireless] Validate: removing stale receiver after" (stale-device-cooldown-ticks)
                    "ticks — :wireless-receiver capability missing:" k)
          (remove-receiver! conn r))
        (doseq [g stale-gens
                :let [k (vb/vblock-to-string g)]
                :when (>= (get counters k 0) (stale-device-cooldown-ticks))]
          (log/warn "[wireless] Validate: removing stale generator after" (stale-device-cooldown-ticks)
                    "ticks — :wireless-generator capability missing:" k)
          (remove-generator! conn g))
        ;; --- whole-connection dispose: node gone or everything empty ---
        (let [conn (entity-commit/resolve-connection (:world-data conn) conn)]
          (if (and (not (is-disposed? conn))
                   (vb/is-chunk-loaded? node-vb world))
            (let [node-exists? (some? (vb/vblock-get node-vb world))
                  empty? (and (zero? (count (get-generators conn)))
                              (zero? (count (get-receivers conn))))]
              (if (or (not node-exists?) empty?)
                (do (when empty?
                      (log/info "[wireless] Validate: disposing empty connection for node"
                                (vb/vblock-to-string node-vb)))
                    (set-disposed! conn true)
                    false)
                true))
            (not (is-disposed? conn))))))))
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
                      (when (log/debug-enabled?)
                        (log/debug (format "[transfer-from-generators!] skipping %s — gen-cap nil"
                                           (vb/vblock-to-string gen-vb))))
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

                    ;; rec-cap is nil — tile exists but its :wireless-receiver capability
                    ;; could not be resolved yet (e.g. multiblock controller).
                    ;; Don't remove; just skip this tick and try again next time.
                    (do
                      (when (log/debug-enabled?)
                        (log/debug (format "[transfer-to-receivers!] skipping %s — rec-cap nil (:wireless-receiver capability not resolved)"
                                           (vb/vblock-to-string rec-vb))))
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
      (let [chunk-loaded? (vb/is-chunk-loaded? receiver-vb world)]
        (if-not chunk-loaded?
          ;; Chunk not loaded — cannot verify, save to avoid data loss
          (nbt/nbt-append! receivers-list (vblock-codec/vblock-to-nbt receiver-vb))
          (if-let [cap (resolver/resolve-receiver-cap world receiver-vb)]
            (nbt/nbt-append! receivers-list (vblock-codec/vblock-to-nbt receiver-vb))
            (log/warn "[wireless] Save: skipping receiver, chunk loaded but :wireless-receiver capability missing — device removed or replaced:"
                      (vb/vblock-to-string receiver-vb))))))
    (doseq [generator-vb (get-generators conn)]
      (let [chunk-loaded? (vb/is-chunk-loaded? generator-vb world)]
        (if-not chunk-loaded?
          (nbt/nbt-append! generators-list (vblock-codec/vblock-to-nbt generator-vb))
          (if-let [cap (resolver/resolve-generator-cap world generator-vb)]
            (nbt/nbt-append! generators-list (vblock-codec/vblock-to-nbt generator-vb))
            (log/warn "[wireless] Save: skipping generator, chunk loaded but :wireless-generator capability missing — device removed or replaced:"
                      (vb/vblock-to-string generator-vb))))))
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


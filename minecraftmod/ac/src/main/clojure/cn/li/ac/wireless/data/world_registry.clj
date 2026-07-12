(ns cn.li.ac.wireless.data.world-registry
  "World-scoped registry and base data model for wireless runtime state.

  Per-world state stored in Framework [:service :wireless-worlds].
  Eliminates the volatile! singleton + ^:dynamic *world-registry-runtime*
  in favor of Framework atom access. Each world's topology data is isolated
  by world-key — zero cross-world CAS contention.

  WiWorldData no longer stores :world (ServerLevel) or :runtime references.
  Callers that need world access must pass it explicitly."
  (:require [cn.li.ac.wireless.core.spatial-index :as si]
            [cn.li.mcmod.events.world-state-notify :as world-state-notify]
            [cn.li.mcmod.platform.world-owner-key :as world-owner-key]
            [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; Data Model
;; ============================================================================

(defrecord WiWorldData [world-key])

(defn initial-world-state
  "Return a fresh per-world topology map (pure data, NBT-serializable).

  Entities are stored in position-keyed maps (pos = [x y z]); lookup tables
  hold position references, never entity values — one entity, one home."
  []
  {:networks       {}    ; {matrix-pos -> WirelessNet}
   :connections    {}    ; {node-pos -> NodeConn}
   :net-by-ssid    {}    ; {ssid -> matrix-pos}
   :node-to-net    {}    ; {node-pos -> matrix-pos}
   :device-to-node {}    ; {generator/receiver-pos -> node-pos}
   :spatial-index  (si/create-spatial-index-value)}) ; {chunk-key -> #{[x y z]}}, placed node blocks only

;; ============================================================================
;; Framework paths
;; ============================================================================

(def ^:private worlds-path [:service :wireless-worlds])

;; Transient runtime state (stale timestamps etc.): never persisted, never
;; fires the world-state-changed notify — mutating it must not mark the
;; SavedData dirty.
(def ^:private transient-root-path [:service :wireless-transient])

(defn- registry-path [world-key]
  (conj worlds-path world-key))

(defn- world-data-path [world-key]
  (conj (registry-path world-key) :world-data))

(defn- world-state-path [world-key]
  (conj (registry-path world-key) :world-state))

;; ============================================================================
;; World key
;; ============================================================================

(defn world-key
  "Return the stable registry key for a world.
   The key intentionally avoids using the mutable world object identity directly."
  [world]
  (world-owner-key/world-key world))

;; ============================================================================
;; State access helpers
;; ============================================================================

(defn- ensure-world-entry!
  "Ensure the world-key entry exists in the registry. Returns nil."
  [world-key]
  (when-let [fw-atom (fw/fw-atom)]
    (swap! fw-atom update-in (registry-path world-key)
           (fn [current]
             (if current
               current
               {:world-data (->WiWorldData world-key)
                :world-state (initial-world-state)}))))
  nil)

(defn- get-world-state
  "Read world-state for a world-key. Returns nil if not found."
  [world-key]
  (when-let [fw-atom (fw/fw-atom)]
    (get-in @fw-atom (world-state-path world-key))))

(defn- get-world-data-record
  "Read WiWorldData for a world-key. Returns nil if not found."
  [world-key]
  (when-let [fw-atom (fw/fw-atom)]
    (get-in @fw-atom (world-data-path world-key))))

;; ============================================================================
;; World-state change hook — called by update-world-state! after every mutation.
;; Generic mechanism: any platform layer (forge/fabric) registers a
;; (fn [world-key]) callback to react to state changes (e.g. marking SavedData
;; dirty). Not wireless-specific — works for any data stored in world-state.
;; ============================================================================

(defn set-on-world-state-changed-fn!
  "Register a (fn [world-key]) callback invoked after every world-state mutation.
  Delegates to the mcmod platform-neutral notify seam."
  [f]
  (world-state-notify/set-on-world-state-changed-fn! f))

(defn- update-world-state!
  "Atomically update world-state via Framework swap!."
  [world-key f & args]
  (when-let [fw-atom (fw/fw-atom)]
    (let [new-fw-state (swap! fw-atom update-in (world-state-path world-key)
                         (fn [current]
                           (let [base (or current (initial-world-state))]
                             (apply f base args))))]
      (world-state-notify/notify-world-state-changed! world-key)
      (get-in new-fw-state (world-state-path world-key)))))

;; ============================================================================
;; World data lifecycle
;; ============================================================================

(defn create-world-data
  "Create new world data for a world."
  [world]
  (let [wk (world-key world)]
    (ensure-world-entry! wk)
    (get-world-data-record wk)))

(defn get-world-data
  "Get world data for a world, creating it if missing."
  [world]
  (let [wk (world-key world)]
    (ensure-world-entry! wk)
    (get-world-data-record wk)))

(defn get-world-data-non-create
  "Get world data without creating."
  [world]
  (get-world-data-record (world-key world)))

(defn register-world-data!
  "Register a world → WiWorldData mapping in the registry.
  Preserves any existing world-state already populated by deserialization
  (e.g. world-data-from-nbt via rebuild-network-indexes!)."
  [world wi-data]
  (let [wk (world-key world)]
    (when-let [fw-atom (fw/fw-atom)]
      (swap! fw-atom update-in (registry-path wk)
             (fn [existing]
               (let [existing-state (or (:world-state existing) (initial-world-state))]
                 {:world-data (assoc wi-data :world-key wk)
                  :world-state existing-state}))))
    wi-data))

(defn remove-world-data!
  "Remove world data and its transient runtime state (called on world unload)."
  [world]
  (let [wk (world-key world)]
    (when-let [fw-atom (fw/fw-atom)]
      (swap! fw-atom (fn [fw-state]
                       (-> fw-state
                           (update-in worlds-path dissoc wk)
                           (update-in transient-root-path dissoc wk))))))
  (log/info (format "Removed WiWorldData for world: %s" (world-key world))))

(defn- remove-session-entries
  [entries session-id]
  (if entries
    (into {} (remove (fn [[[entry-session-id _world-id] _data]]
                       (= session-id entry-session-id))
                     entries))
    {}))

(defn clear-session-world-data!
  "Remove all wireless world data (and transient state) owned by one server session."
  [owner-or-session-id]
  (let [session-id (if (map? owner-or-session-id)
                     (first (world-owner-key/world-key owner-or-session-id))
                     owner-or-session-id)]
    (when-let [fw-atom (fw/fw-atom)]
      (swap! fw-atom (fn [fw-state]
                       (-> fw-state
                           (update-in worlds-path remove-session-entries session-id)
                           (update-in transient-root-path remove-session-entries session-id)))))
    nil))

;; ============================================================================
;; State CRUD
;; ============================================================================

(defn world-state
  "Read the entire world-state map once (preferred in tick paths)."
  [world-data]
  (or (get-world-state (:world-key world-data)) (initial-world-state)))

(defn state-value
  "Read a key from world-state."
  [world-data key]
  (get (world-state world-data) key))

(defn set-state-value!
  "Set a key in world-state."
  [world-data key value]
  (update-world-state! (:world-key world-data) assoc key value)
  value)

(defn update-state-value!
  "Update a key in world-state via function."
  [world-data key f & args]
  (apply update-world-state! (:world-key world-data) update key f args)
  (state-value world-data key))

(defn update-state!
  "Apply a function to the entire world-state."
  [world-data f & args]
  (apply update-world-state! (:world-key world-data) f args))

;; ============================================================================
;; Transient runtime state — per world-key, outside world-state: mutations do
;; NOT fire notify-world-state-changed! (no SavedData dirty, no NBT).
;; ============================================================================

(defn transient-value
  "Read a key from this world's transient runtime state."
  [world-data key]
  (when-let [fw-atom (fw/fw-atom)]
    (get-in @fw-atom (conj transient-root-path (:world-key world-data) key))))

(defn update-transient-value!
  "Update a key in this world's transient runtime state. Returns nil."
  [world-data key f & args]
  (when-let [fw-atom (fw/fw-atom)]
    (swap! fw-atom update-in (conj transient-root-path (:world-key world-data) key)
           (fn [current] (apply f current args))))
  nil)

;; ============================================================================
;; Domain accessors
;; ============================================================================

(defn networks
  "Position-keyed network map: {matrix-pos -> WirelessNet}."
  [world-data]
  (state-value world-data :networks))

(defn connections
  "Position-keyed connection map: {node-pos -> NodeConn}."
  [world-data]
  (state-value world-data :connections))

(defn net-by-ssid [world-data] (state-value world-data :net-by-ssid))
(defn node-to-net [world-data] (state-value world-data :node-to-net))
(defn device-to-node [world-data] (state-value world-data :device-to-node))
(defn spatial-index [world-data] (state-value world-data :spatial-index))

;; ============================================================================
;; Diagnostics & testing
;; ============================================================================

(defn registry-snapshot
  "Return current in-memory registry snapshot. Intended for tests/diagnostics."
  []
  (if-let [fw-atom (fw/fw-atom)]
    (get-in @fw-atom worlds-path)
    {}))

(defn reset-registry!
  "Reset in-memory world registry. Intended for tests only."
  []
  (when-let [fw-atom (fw/fw-atom)]
    (swap! fw-atom assoc-in worlds-path {})))

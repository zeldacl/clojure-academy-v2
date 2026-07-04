(ns cn.li.ac.wireless.data.world-registry
  "World-scoped registry and base data model for wireless runtime state.

  Per-world state stored in Framework [:service :wireless-worlds].
  Eliminates the volatile! singleton + ^:dynamic *world-registry-runtime*
  in favor of Framework atom access. Each world's topology data is isolated
  by world-key — zero cross-world CAS contention.

  WiWorldData no longer stores :world (ServerLevel) or :runtime references.
  Callers that need world access must pass it explicitly."
  (:require [cn.li.ac.wireless.core.spatial-index :as si]
            [cn.li.mcmod.platform.world-owner-key :as world-owner-key]
            [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; Data Model
;; ============================================================================

(defrecord WiWorldData [world-key])

(defn initial-world-state
  "Return a fresh per-world topology map (pure data, NBT-serializable)."
  []
  {:net-lookup   {}
   :node-lookup  {}
   :spatial-index (si/create-spatial-index-value)
   :networks     []
   :connections  []})

;; ============================================================================
;; Framework paths
;; ============================================================================

(def ^:private worlds-path [:service :wireless-worlds])

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

(def ^:private on-world-state-changed-fn (atom nil))

(defn set-on-world-state-changed-fn!
  "Register a (fn [world-key]) callback invoked after every world-state mutation.
  Platform layers use this to mark persistent SavedData dirty, so Forge's save
  cycle picks up changes made by ANY module that writes via update-world-state!."
  [f]
  (reset! on-world-state-changed-fn f)
  nil)

(defn- update-world-state!
  "Atomically update world-state via Framework swap!."
  [world-key f & args]
  (when-let [fw-atom (fw/fw-atom)]
    (let [new-fw-state (swap! fw-atom update-in (world-state-path world-key)
                         (fn [current]
                           (let [base (or current (initial-world-state))]
                             (apply f base args))))]
      (when-let [on-change @on-world-state-changed-fn]
        (on-change world-key))
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
  "Register a world -> WiWorldData mapping in the registry."
  [world wi-data]
  (let [wk (world-key world)
        state (initial-world-state)]
    (when-let [fw-atom (fw/fw-atom)]
      (swap! fw-atom assoc-in (registry-path wk)
             {:world-data (assoc wi-data :world-key wk)
              :world-state state}))
    wi-data))

(defn remove-world-data!
  "Remove world data (called on world unload)."
  [world]
  (let [wk (world-key world)]
    (when-let [fw-atom (fw/fw-atom)]
      (swap! fw-atom update-in worlds-path dissoc wk)))
  (log/info (format "Removed WiWorldData for world: %s" (world-key world))))

(defn clear-session-world-data!
  "Remove all wireless world data owned by one server session."
  [owner-or-session-id]
  (let [session-id (if (map? owner-or-session-id)
                     (first (world-owner-key/world-key owner-or-session-id))
                     owner-or-session-id)]
    (when-let [fw-atom (fw/fw-atom)]
      (swap! fw-atom update-in worlds-path
             (fn [worlds]
               (if worlds
                 (into {} (remove (fn [[[entry-session-id _world-id] _data]]
                                    (= session-id entry-session-id))
                                  worlds))
                 {}))))
    nil))

;; ============================================================================
;; State CRUD
;; ============================================================================

(defn state-value
  "Read a key from world-state."
  [world-data key]
  (get (or (get-world-state (:world-key world-data)) (initial-world-state)) key))

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
;; Domain accessors
;; ============================================================================

(defn net-lookup [world-data] (state-value world-data :net-lookup))
(defn node-lookup [world-data] (state-value world-data :node-lookup))
(defn spatial-index [world-data] (state-value world-data :spatial-index))
(defn networks [world-data] (state-value world-data :networks))
(defn connections [world-data] (state-value world-data :connections))

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

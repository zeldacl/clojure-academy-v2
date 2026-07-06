(ns cn.li.ac.ability.service.runtime-store
  "Unified runtime store for ability system state.

  Replaces the single global atom (CAS contention under multi-player load)
  with per-player atoms stored in a ConcurrentHashMap. Each player's state
  is independently swappable — 50 concurrent players = 50 independent atoms,
  zero CAS retry storms.

  Store structure:
    player-atoms: ConcurrentHashMap<\"session-id:player-uuid\", Atom<player-state>>
    sessions:     ConcurrentHashMap<session-id, ConcurrentHashMap<player-uuid, true>>

  Player state map:
    {:ability-data     {...}  ; level/progress/learned-skills/exp-map
     :resource-data    {...}  ; cp/overload/activated/interferences
     :cooldown-data    {...}  ; {[ctrl-id sub-id] ticks}
     :preset-data      {...}  ; active-preset/slots
     :context-registry {...}  ; {ctx-id {:id :skill-id :status :player-uuid}}
     :dirty?           bool}

  Note: terminal-data and tutorial-data are persisted directly to player
  NBT (see terminal/player.clj and tutorial/player.clj), not via this store."
  (:require [cn.li.ac.ability.model.ability :as adata]
            [cn.li.ac.ability.model.resource :as rdata]
            [cn.li.ac.ability.model.preset :as pdata]
            [cn.li.mcmod.framework :as fw])
  (:import [java.util.concurrent ConcurrentHashMap]))

;; ============================================================================
;; Default Player State
;; ============================================================================

(defn fresh-player-state
  "Create a default empty player state map.
   cooldown-data and develop-data are transient (upstream CooldownData/DevelopData
   do not call setNBTStorage). They are lazily initialized on first access."
  []
  {:ability-data     (adata/new-ability-data)
   :resource-data    (rdata/new-resource-data)
   :preset-data      (pdata/new-preset-data)
   :context-registry {}
   :dirty?           false})

;; ============================================================================
;; Player key encoding
;; ============================================================================

(defn- player-key
  "Composite key for per-player atom lookup."
  [session-id player-uuid]
  (str session-id ":" player-uuid))

;; ============================================================================
;; Framework-backed Store (at [:service :ability-store])
;;
;; Each player gets a PRIVATE atom. swap! on player A's atom never retries
;; due to player B's concurrent modification — zero cross-player CAS contention.
;; ============================================================================

(def ^:private store-path [:service :ability-store])

(defn- ensure-store
  "Get or create the ability store from the framework atom.
   Returns nil if the framework is not yet initialized."
  []
  (when-let [fw-atom (fw/fw-atom)]
    (or (get-in @fw-atom store-path)
        (let [store {:player-atoms (ConcurrentHashMap.) :sessions (ConcurrentHashMap.)}]
          (swap! fw-atom assoc-in store-path store)
          store))))

;; ============================================================================
;; Store operations (ex-protocol methods)
;; ============================================================================

(defn get-player-state
  "Get full player state map. Returns nil if not found."
  [session-id player-uuid]
  (when-let [store (ensure-store)]
    (when-let [a (.get ^ConcurrentHashMap (:player-atoms store) (player-key session-id player-uuid))]
      @a)))

(defn set-player-state!
  "Replace full player state map. Returns nil."
  [session-id player-uuid state]
  (when-let [store (ensure-store)]
    (let [k (player-key session-id player-uuid)
          player-atoms (:player-atoms store)
          sessions (:sessions store)]
      (.putIfAbsent ^ConcurrentHashMap sessions session-id (ConcurrentHashMap.))
      (if-let [a (.get ^ConcurrentHashMap player-atoms k)]
        (reset! a state)
        (.put ^ConcurrentHashMap player-atoms k (atom state)))
      (when-let [^ConcurrentHashMap s (.get ^ConcurrentHashMap sessions session-id)]
        (.put ^ConcurrentHashMap s player-uuid true))))
  nil)

(defn update-player-state!
  "Apply f to player state. Returns updated state."
  [session-id player-uuid f]
  (when-let [store (ensure-store)]
    (let [k (player-key session-id player-uuid)
          player-atoms (:player-atoms store)
          a (or (.get ^ConcurrentHashMap player-atoms k)
                (let [new-a (atom nil)]
                  (if-let [existing (.putIfAbsent ^ConcurrentHashMap player-atoms k new-a)]
                    existing
                    new-a)))]
      (swap! a (fn [current] (f (or current (fresh-player-state))))))))

(defn update-player-state-domain!
  "Apply f to a domain sub-key (e.g., :ability-data). Returns updated state."
  [session-id player-uuid domain-key f]
  (when-let [store (ensure-store)]
    (let [k (player-key session-id player-uuid)
          player-atoms (:player-atoms store)
          a (or (.get ^ConcurrentHashMap player-atoms k)
                (let [new-a (atom nil)]
                  (if-let [existing (.putIfAbsent ^ConcurrentHashMap player-atoms k new-a)]
                    existing
                    new-a)))]
      (swap! a (fn [current]
                 (let [base (or current (fresh-player-state))]
                   (update base domain-key f)))))))

(defn mark-dirty!
  "Mark player state as needing persistence flush. Returns nil."
  [session-id player-uuid]
  (when-let [store (ensure-store)]
    (when-let [a (.get ^ConcurrentHashMap (:player-atoms store) (player-key session-id player-uuid))]
      (swap! a assoc :dirty? true)))
  nil)

(defn get-dirty-players
  "List of player-uuids with :dirty? = true in this session. Returns [...]."
  [session-id]
  (when-let [store (ensure-store)]
    (let [player-atoms (:player-atoms store)
          s (.get ^ConcurrentHashMap (:sessions store) session-id)]
      (if s
        (let [dirty (java.util.ArrayList.)]
          (doseq [uuid (enumeration-seq (.keys ^ConcurrentHashMap s))]
            (when-let [a (.get ^ConcurrentHashMap player-atoms (player-key session-id uuid))]
              (when (:dirty? @a)
                (.add dirty uuid))))
          (vec dirty))
        []))))

(defn clear-dirty!
  "Clear the dirty flag. Returns nil."
  ([session-id player-uuid]
   (when-let [store (ensure-store)]
     (when-let [a (.get ^ConcurrentHashMap (:player-atoms store) (player-key session-id player-uuid))]
       (swap! a assoc :dirty? false)))
   nil)
  ([_store session-id player-uuid]
   (clear-dirty! session-id player-uuid)))

(defn create-session!
  "Initialize a session. Returns nil."
  ([session-id]
   (when-let [store (ensure-store)]
     (.putIfAbsent ^ConcurrentHashMap (:sessions store) session-id (ConcurrentHashMap.)))
   nil)
  ([_store session-id]
   (create-session! session-id)))

(defn remove-session!
  "Remove all state for a session. Returns nil."
  ([session-id]
   (when-let [store (ensure-store)]
     (let [player-atoms (:player-atoms store)
           sessions (:sessions store)]
       (when-let [s (.get ^ConcurrentHashMap sessions session-id)]
         (doseq [uuid (enumeration-seq (.keys ^ConcurrentHashMap s))]
           (.remove ^ConcurrentHashMap player-atoms (player-key session-id uuid))))
       (.remove ^ConcurrentHashMap sessions session-id))
     nil))
  ([_store session-id]
   (remove-session! session-id)))

(defn remove-player-state!
  "Remove one player state from a session. Returns nil."
  [session-id player-uuid]
  (when-let [store (ensure-store)]
    (.remove ^ConcurrentHashMap (:player-atoms store) (player-key session-id player-uuid))
    (when-let [s (.get ^ConcurrentHashMap (:sessions store) session-id)]
      (.remove ^ConcurrentHashMap s player-uuid)))
  nil)

(defn list-sessions
  "Return all active session-ids."
  []
  (when-let [store (ensure-store)]
    (enumeration-seq (.keys ^ConcurrentHashMap (:sessions store)))))

(defn list-players
  "Return all player-uuids in a session."
  ([session-id]
   (when-let [store (ensure-store)]
     (if-let [s (.get ^ConcurrentHashMap (:sessions store) session-id)]
       (enumeration-seq (.keys ^ConcurrentHashMap s))
       ())))
  ([_store session-id]
   (list-players session-id)))

(defn snapshot
  "Return a full read-only copy of all store data (for testing/debugging)."
  []
  (when-let [store (ensure-store)]
    (let [player-atoms (:player-atoms store)
          sessions (:sessions store)
          result (atom {:sessions {}})]
      (doseq [session-id (enumeration-seq (.keys ^ConcurrentHashMap sessions))]
        (let [s (.get ^ConcurrentHashMap sessions session-id)
              players (atom {})]
          (when s
            (doseq [uuid (enumeration-seq (.keys ^ConcurrentHashMap s))]
              (when-let [a (.get ^ConcurrentHashMap player-atoms (player-key session-id uuid))]
                (swap! players assoc uuid @a))))
          (swap! result assoc-in [:sessions session-id :players] @players)))
      @result)))

;; ============================================================================
;; For backward compatibility: old callers pass (store/get-store) as first arg.
;; New code just passes session-id. Accept both.
(defn- compat-args [args]
  (if (and (= 2 (count args)) (map? (first args)))
    (rest args)
    args))
;; ============================================================================

(defn get-store
  "Return the ability store instance. Created lazily on first call."
  []
  (ensure-store))

(defn reset-store!
  "Clear all store data. Primarily for testing."
  []
  (when-let [fw-atom (fw/fw-atom)]
    (swap! fw-atom assoc-in store-path {:player-atoms (ConcurrentHashMap.) :sessions (ConcurrentHashMap.)}))
  nil)

;; ============================================================================
;; Convenience functions (delegate to store)
;; ============================================================================

(defn get-player-state*
  "Get player state from the store."
  [session-id player-uuid]
  (get-player-state session-id player-uuid))

(defn set-player-state!*
  "Set player state in the store."
  [session-id player-uuid state]
  (set-player-state! session-id player-uuid state))

(defn update-player-state!*
  "Update player state in the store."
  [session-id player-uuid f & args]
  (if (seq args)
    (update-player-state! session-id player-uuid
                          (fn [state] (apply f state args)))
    (update-player-state! session-id player-uuid f)))

(defn mark-player-dirty!
  "Mark player dirty in the store."
  [session-id player-uuid]
  (mark-dirty! session-id player-uuid))

(defn get-or-create-player-state!
  "Get player state or create it with fresh defaults if missing."
  [session-id player-uuid]
  (or (get-player-state* session-id player-uuid)
      (let [fresh (fresh-player-state)]
        (set-player-state!* session-id player-uuid fresh)
        fresh)))

(defn remove-player-state!*
  "Remove one player state in the store."
  [session-id player-uuid]
  (remove-player-state! session-id player-uuid))

;; ============================================================================
;; Reducer support
;; ============================================================================

(defn with-player-state
  "Apply f to player state and return f's result without mutating store.
  Used for pure data extraction in reducers."
  [session-id player-uuid f]
  (f (get-player-state* session-id player-uuid)))

(defn apply-reducer-result!
  "Commit a reducer result {:state ...} back to the store.

  Marks dirty if :state is non-nil and different from current.
  Returns the new state."
  [session-id player-uuid {:keys [state]}]
  (when state
    (let [current (get-player-state* session-id player-uuid)]
      (when-not (identical? state current)
        (set-player-state!* session-id player-uuid state)
        (mark-player-dirty! session-id player-uuid))))
  state)

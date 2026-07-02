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
            [cn.li.ac.ability.model.preset :as pdata])
  (:import [java.util.concurrent ConcurrentHashMap]
           [java.util Collections]))

;; ============================================================================
;; Store Protocol
;; ============================================================================

(defprotocol IAbilityStore
  "Primary state management interface for ability system.

  All implementations must be thread-safe."

  (get-player-state [store session-id player-uuid]
    "Get full player state map. Returns nil if not found.")

  (set-player-state! [store session-id player-uuid state]
    "Replace full player state map. Returns nil.")

  (update-player-state! [store session-id player-uuid f]
    "Apply f to player state. Returns updated state.")

  (update-player-state-domain! [store session-id player-uuid domain-key f]
    "Apply f to a domain sub-key (e.g., :ability-data). Returns updated state.")

  (mark-dirty! [store session-id player-uuid]
    "Mark player state as needing persistence flush. Returns nil.")

  (get-dirty-players [store session-id]
    "List of player-uuids with :dirty? = true in this session. Returns [...].")

  (clear-dirty! [store session-id player-uuid]
    "Clear the dirty flag. Returns nil.")

  (create-session! [store session-id]
    "Initialize a session. Returns nil.")

  (remove-session! [store session-id]
    "Remove all state for a session. Returns nil.")

  (remove-player-state! [store session-id player-uuid]
    "Remove one player state from a session. Returns nil.")

  (list-sessions [store]
    "Return all active session-ids.")

  (list-players [store session-id]
    "Return all player-uuids in a session.")

  (snapshot [store]
    "Return a full read-only copy of all store data (for testing/debugging)."))

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
;; Per-Player Atom Store (ConcurrentHashMap-backed)
;;
;; Each player gets a PRIVATE atom. swap! on player A's atom never retries
;; due to player B's concurrent modification — zero cross-player CAS contention.
;; ============================================================================

(deftype AtomAbilityStore [^ConcurrentHashMap player-atoms
                            ^ConcurrentHashMap sessions]
  IAbilityStore

  (get-player-state [_ session-id player-uuid]
    (when-let [a (.get ^ConcurrentHashMap player-atoms (player-key session-id player-uuid))]
      @a))

  (set-player-state! [_ session-id player-uuid state]
    (let [k (player-key session-id player-uuid)]
      (if-let [a (.get ^ConcurrentHashMap player-atoms k)]
        (reset! a state)
        (.put ^ConcurrentHashMap player-atoms k (atom state))))
    ;; Track in session index
    (when-let [^ConcurrentHashMap s (.get ^ConcurrentHashMap sessions session-id)]
      (.put ^ConcurrentHashMap s player-uuid true))
    nil)

  (update-player-state! [_ session-id player-uuid f]
    (let [k (player-key session-id player-uuid)
          a (or (.get ^ConcurrentHashMap player-atoms k)
                (let [new-a (atom nil)]
                  (if-let [existing (.putIfAbsent ^ConcurrentHashMap player-atoms k new-a)]
                    existing
                    new-a)))]
      (swap! a (fn [current] (f (or current (fresh-player-state)))))))

  (update-player-state-domain! [_ session-id player-uuid domain-key f]
    (let [k (player-key session-id player-uuid)
          a (or (.get ^ConcurrentHashMap player-atoms k)
                (let [new-a (atom nil)]
                  (if-let [existing (.putIfAbsent ^ConcurrentHashMap player-atoms k new-a)]
                    existing
                    new-a)))]
      (swap! a (fn [current]
                 (let [base (or current (fresh-player-state))]
                   (update base domain-key f))))))

  (mark-dirty! [_ session-id player-uuid]
    (when-let [a (.get ^ConcurrentHashMap player-atoms (player-key session-id player-uuid))]
      (swap! a assoc :dirty? true))
    nil)

  (get-dirty-players [_ session-id]
    (let [s (.get ^ConcurrentHashMap sessions session-id)]
      (if s
        (let [dirty (java.util.ArrayList.)]
          (doseq [uuid (enumeration-seq (.keys ^ConcurrentHashMap s))]
            (when-let [a (.get ^ConcurrentHashMap player-atoms (player-key session-id uuid))]
              (when (:dirty? @a)
                (.add dirty uuid))))
          (vec dirty))
        [])))

  (clear-dirty! [_ session-id player-uuid]
    (when-let [a (.get ^ConcurrentHashMap player-atoms (player-key session-id player-uuid))]
      (swap! a assoc :dirty? false))
    nil)

  (create-session! [_ session-id]
    (.putIfAbsent ^ConcurrentHashMap sessions session-id (ConcurrentHashMap.))
    nil)

  (remove-session! [_ session-id]
    (when-let [s (.get ^ConcurrentHashMap sessions session-id)]
      (doseq [uuid (enumeration-seq (.keys ^ConcurrentHashMap s))]
        (.remove ^ConcurrentHashMap player-atoms (player-key session-id uuid))))
    (.remove ^ConcurrentHashMap sessions session-id)
    nil)

  (remove-player-state! [_ session-id player-uuid]
    (.remove ^ConcurrentHashMap player-atoms (player-key session-id player-uuid))
    (when-let [s (.get ^ConcurrentHashMap sessions session-id)]
      (.remove ^ConcurrentHashMap s player-uuid))
    nil)

  (list-sessions [_]
    (enumeration-seq (.keys ^ConcurrentHashMap sessions)))

  (list-players [_ session-id]
    (if-let [s (.get ^ConcurrentHashMap sessions session-id)]
      (enumeration-seq (.keys ^ConcurrentHashMap s))
      ()))

  (snapshot [_]
    (let [result (atom {:sessions {}})]
      (doseq [session-id (enumeration-seq (.keys ^ConcurrentHashMap sessions))]
        (let [s (.get ^ConcurrentHashMap sessions session-id)
              players (volatile! {})]
          (when s
            (doseq [uuid (enumeration-seq (.keys ^ConcurrentHashMap s))]
              (when-let [a (.get ^ConcurrentHashMap player-atoms (player-key session-id uuid))]
                (vswap! players assoc uuid @a))))
          (swap! result assoc-in [:sessions session-id :players] @players)))
      @result)))

;; ============================================================================
;; Store access — lazy-init factory, no top-level defonce singleton
;; ============================================================================

(let [store-instance (volatile! nil)]
  (defn- ensure-store
    "Create the ability store on first access.
     Uses ConcurrentHashMap for per-player atom isolation."
    []
    (or @store-instance
        (let [store (AtomAbilityStore. (ConcurrentHashMap.) (ConcurrentHashMap.))]
          (vreset! store-instance store)
          store)))

  (defn get-store
    "Return the ability store instance. Created lazily on first call."
    []
    (ensure-store))

  (defn reset-store!
    "Clear all store data. Primarily for testing."
    []
    (when-let [store @store-instance]
      (.clear ^ConcurrentHashMap (.player-atoms ^AtomAbilityStore store))
      (.clear ^ConcurrentHashMap (.sessions ^AtomAbilityStore store)))
    nil)

  ;; ============================================================================
  ;; Convenience functions (delegate to store)
  ;; ============================================================================

  (defn get-player-state*
    "Get player state from the store."
    [session-id player-uuid]
    (get-player-state (ensure-store) session-id player-uuid))

  (defn set-player-state!*
    "Set player state in the store."
    [session-id player-uuid state]
    (set-player-state! (ensure-store) session-id player-uuid state))

  (defn update-player-state!*
    "Update player state in the store."
    [session-id player-uuid f & args]
    (if (seq args)
      (update-player-state! (ensure-store)
                            session-id
                            player-uuid
                            (fn [state]
                              (apply f state args)))
      (update-player-state! (ensure-store) session-id player-uuid f))))

  (defn mark-player-dirty!
    "Mark player dirty in the store."
    [session-id player-uuid]
    (mark-dirty! (ensure-store) session-id player-uuid))

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
    (remove-player-state! (ensure-store) session-id player-uuid))

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

(ns cn.li.ac.ability.service.runtime-store
  "Unified runtime store for ability system state.
  
  Replaces 30+ scattered atoms with a single centralized store keyed by
  [session-id player-uuid]. This is the primary state management boundary
  between the Functional Core (reducers/rules) and Imperative Shell (network/hooks).

  Store structure:
    {:sessions
     {session-id
      {:players
       {player-uuid
        {:ability-data     {...}  ; level/progress/learned-skills/exp-map
         :resource-data    {...}  ; cp/overload/activated/interferences
         :cooldown-data    {...}  ; {[ctrl-id sub-id] ticks}
         :preset-data      {...}  ; active-preset/slots
         :context-registry {...}  ; {ctx-id {:id :skill-id :status :player-uuid}}
         :dirty?           bool}}}}}

  Note: terminal-data and tutorial-data are now persisted directly to player
  NBT (see terminal/player.clj and tutorial/player.clj), not via this store.
  
  Usage:
    ;; Get/set player state
    (get-player-state [session-id player-uuid])
    (update-player-state! [session-id player-uuid] f & args)
    
    ;; Session lifecycle
    (create-session! session-id)
    (remove-session! session-id)
    
    ;; Testing
    (reset-store!)
    (snapshot)"
  (:require [cn.li.ac.ability.model.ability :as adata]
            [cn.li.ac.ability.model.resource :as rdata]
            [cn.li.ac.ability.model.preset :as pdata]))

;; ============================================================================
;; Store Protocol
;; ============================================================================

(defprotocol IAbilityStore
  "Primary state management interface for ability system.
  
  All implementations must be thread-safe. The default implementation
  uses an atom for single-JVM scenarios."
  
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
;; Default Implementation (Atom-based, single-JVM)
;; ============================================================================

(deftype AtomAbilityStore [store-atom]
  IAbilityStore

  (get-player-state [_ session-id player-uuid]
    (get-in @store-atom [:sessions session-id :players player-uuid]))

  (set-player-state! [_ session-id player-uuid state]
    (swap! store-atom assoc-in [:sessions session-id :players player-uuid] state)
    nil)

  (update-player-state! [_ session-id player-uuid f]
    (let [result (atom nil)]
      (swap! store-atom
             (fn [store]
               (let [current (get-in store [:sessions session-id :players player-uuid])
                     updated (f current)]
                 (reset! result updated)
                 (assoc-in store [:sessions session-id :players player-uuid] updated))))
      @result))

  (update-player-state-domain! [_ session-id player-uuid domain-key f]
    (let [result (atom nil)]
      (swap! store-atom
             (fn [store]
               (let [current (get-in store [:sessions session-id :players player-uuid])
                     updated (update current domain-key f)]
                 (reset! result updated)
                 (assoc-in store [:sessions session-id :players player-uuid] updated))))
      @result))

  (mark-dirty! [_ session-id player-uuid]
    (swap! store-atom assoc-in
           [:sessions session-id :players player-uuid :dirty?] true)
    nil)

  (get-dirty-players [_ session-id]
    (->> (get-in @store-atom [:sessions session-id :players])
         (filter (fn [[_ state]] (:dirty? state)))
         (mapv first)))

  (clear-dirty! [_ session-id player-uuid]
    (swap! store-atom assoc-in
           [:sessions session-id :players player-uuid :dirty?] false)
    nil)

  (create-session! [_ session-id]
    (swap! store-atom update-in [:sessions] #(if (get % session-id)
                                               %
                                               (assoc % session-id {:players {}})))
    nil)

  (remove-session! [_ session-id]
    (swap! store-atom update :sessions dissoc session-id)
    nil)

  (remove-player-state! [_ session-id player-uuid]
    (swap! store-atom update-in [:sessions session-id :players] dissoc player-uuid)
    nil)

  (list-sessions [_]
    (into [] (keys (:sessions @store-atom))))

  (list-players [_ session-id]
    (into [] (keys (get-in @store-atom [:sessions session-id :players]))))

  (snapshot [_]
    @store-atom))

;; ============================================================================
;; Store access — lazy-init factory, no top-level defonce singleton
;; ============================================================================

(let [store-data (volatile! nil)
      store-instance (volatile! nil)]
  (defn- ensure-store
    "Create the ability store on first access."
    []
    (or @store-instance
        (let [data (atom {:sessions {}})]
          (vreset! store-data data)
          (vreset! store-instance (AtomAbilityStore. data))
          @store-instance)))

  (defn get-store
    "Return the ability store instance. Created lazily on first call."
    []
    (ensure-store))

  (defn reset-store!
    "Clear all store data. Primarily for testing."
    []
    (when-let [data @store-data]
      (reset! data {:sessions {}}))
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

(ns cn.li.ability.session
  "Multi-tenant session index for the shared ability execution boundary.

   Ported from ac's combat_sessions.clj (single-tenant, keyed by owner
   only). Every entry is keyed by [content-id owner ability-id] so AC/BC/CC can each
   hold an active session for the same player without colliding -- a
   player mid-cast on an AC ability and a BC ability at once is a real
   scenario once a second content module exists, not a hypothetical.

   The session value contains only neutral owner/ability/context data; it
   never stores a platform object or content-specific state shape. All
   behavior is still selected by the compiled combat program.")

(defonce ^:private sessions* (atom {}))

(defn- key-for [content-id owner ability-id] [content-id owner ability-id])

(defn start! [content-id owner ability-id intent]
  ;; The caller's own VM run (execute!'s commit-ability-state! -> apply-
  ;; actions! below) can fire before start! does -- apply-actions! uses
  ;; update-in, which auto-vivifies a {:state {...}} entry at this key the
  ;; moment the graph's first :state/write patch lands, ahead of start!
  ;; ever being called for a brand new activation. Preserve whatever :state/
  ;; :latches already accumulated at this key instead of resetting them to
  ;; empty, or the ability's own :start-phase session-state defaults would
  ;; be silently discarded the instant a session opens.
(let [k (key-for content-id owner ability-id)
        prior (get @sessions* k)
        entry {:owner owner
               :content-id content-id
               :ability-id ability-id
               :context (:context intent)
               :parameter-snapshot (:parameter-snapshot intent)
               :activation-seed (long (or (:activation-seed intent)
                                          (hash [content-id owner ability-id])))
               :tick (long (or (:server-tick intent) 0))
               :start-tick (long (or (:server-tick intent) 0))
               :state (or (:state prior) {})
               :latches (or (:latches prior) #{})}]
    (swap! sessions* assoc k entry)
    entry))

(defn active?
  ([content-id owner]
   (boolean (some (fn [[[cid oid _] _]]
                    (and (= cid content-id) (= oid owner))) @sessions*)))
  ([content-id owner ability-id]
   (contains? @sessions* (key-for content-id owner ability-id))))

(defn session
  ([content-id owner]
   (some (fn [[[cid oid _] entry]]
           (when (and (= cid content-id) (= oid owner)) entry)) @sessions*))
  ([content-id owner ability-id]
   (get @sessions* (key-for content-id owner ability-id))))

(defn remove!
  ([content-id owner]
   (swap! sessions* (fn [m]
                      (into {} (remove (fn [[[cid oid _] _]]
                                         (and (= cid content-id) (= oid owner))) m))))
   nil)
  ([content-id owner ability-id]
   (swap! sessions* dissoc (key-for content-id owner ability-id))
   nil))

(defn apply-actions!
  "Apply neutral session patches and latch claims after an accepted VM run.

   The operation is deliberately generic: paths and modes come from the
   compiled program, and this store contains no content-specific keys or
   behavior."
  ([content-id owner actions]
   (let [matches (filter (fn [[[cid oid _] _]]
                           (and (= cid content-id) (= oid owner))) @sessions*)]
     (when (= 1 (count matches))
      (apply-actions! content-id owner (nth (first (first matches)) 2) actions))))
  ([content-id owner ability-id actions]
  (let [k (key-for content-id owner ability-id)
        patches (for [{:keys [type entries]} actions
                      :when (= :session-patch type)
                      entry entries]
                  entry)]
    (when (seq patches)
      (swap! sessions*
             update-in [k :state]
             (fn [state]
               (reduce (fn [result {:keys [path mode value]}]
                         (case mode
                           :increment (update-in result path (fnil + 0.0)
                                                  (double (or value 0.0)))
                           :assign (assoc-in result path value)
                           result))
                       (or state {})
                       patches))))
    (when-let [latches (some (fn [{:keys [type latches]}]
                               (when (= :session-latches type) latches)) actions)]
      (swap! sessions* update-in [k :latches] into latches))
    nil)))

(defn sessions-for-owner
  "Return all active sessions for one content/owner, keyed by ability-id."
  [content-id owner]
  (into {}
        (keep (fn [[[cid oid ability-id] entry]]
                (when (and (= cid content-id) (= oid owner))
                  [ability-id entry])))
        @sessions*))

(defn snapshot-all
  "All sessions for one content module, keyed by [owner ability-id]."
  [content-id]
  (into {}
        (keep (fn [[[cid owner ability-id] entry]]
                (when (= cid content-id)
                  [[owner ability-id] entry])))
        @sessions*))

(defn snapshot
  "All sessions for one content module, keyed by owner only -- the shape
   every existing consumer (a per-tenant pulse loop) expects."
  [content-id]
  (into {} (keep (fn [[[cid owner ability-id] entry]]
                  (when (= cid content-id)
                    [owner (assoc entry :ability-id ability-id)])))
        @sessions*))

(defn reset-for-test!
  ([] (reset! sessions* {}) nil)
  ([content-id]
   (swap! sessions* (fn [m] (into {} (remove (fn [[[cid _ _] _]] (= cid content-id))) m)))
   nil))

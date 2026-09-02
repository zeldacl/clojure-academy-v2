(ns cn.li.ability.session
  "Multi-tenant session index for the shared ability execution boundary.

   Ported from ac's combat_sessions.clj (single-tenant, keyed by owner
   only). Every entry is keyed by [content-id owner] so AC/BC/CC can each
   hold an active session for the same player without colliding -- a
   player mid-cast on an AC ability and a BC ability at once is a real
   scenario once a second content module exists, not a hypothetical.

   The session value contains only neutral owner/ability/context data; it
   never stores a platform object or content-specific state shape. All
   behavior is still selected by the compiled combat program.")

(defonce ^:private sessions* (atom {}))

(defn- key-for [content-id owner] [content-id owner])

(defn start! [content-id owner ability-id intent]
  (let [entry {:owner owner
               :content-id content-id
               :ability-id ability-id
               :context (:context intent)
               :parameter-snapshot (:parameter-snapshot intent)
               :activation-seed (long (or (:activation-seed intent)
                                          (hash [content-id owner ability-id])))
               :tick (long (or (:server-tick intent) 0))
               :start-tick (long (or (:server-tick intent) 0))
               :state {}
               :latches #{}}]
    (swap! sessions* assoc (key-for content-id owner) entry)
    entry))

(defn active? [content-id owner]
  (contains? @sessions* (key-for content-id owner)))

(defn session [content-id owner]
  (get @sessions* (key-for content-id owner)))

(defn remove! [content-id owner]
  (swap! sessions* dissoc (key-for content-id owner))
  nil)

(defn apply-actions!
  "Apply neutral session patches and latch claims after an accepted VM run.

   The operation is deliberately generic: paths and modes come from the
   compiled program, and this store contains no content-specific keys or
   behavior."
  [content-id owner actions]
  (let [k (key-for content-id owner)
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
    nil))

(defn snapshot
  "All sessions for one content module, keyed by owner only -- the shape
   every existing consumer (a per-tenant pulse loop) expects."
  [content-id]
  (into {} (keep (fn [[[cid owner] entry]] (when (= cid content-id) [owner entry])))
        @sessions*))

(defn reset-for-test!
  ([] (reset! sessions* {}) nil)
  ([content-id]
   (swap! sessions* (fn [m] (into {} (remove (fn [[[cid _] _]] (= cid content-id))) m)))
   nil))

(ns cn.li.ac.ability.service.combat-sessions
  "AC-owned session index for the generic combat execution boundary.

  The session value contains only neutral owner/ability/context data.  It
  never stores Minecraft objects or skill-specific state; all behavior is
  still selected by the compiled combat program."
  (:require [cn.li.ac.ability.service.combat-catalog :as catalog]))

(defonce ^:private sessions* (atom {}))

(defn start! [owner ability-id intent]
  (catalog/require-available ability-id)
  (let [entry {:owner owner
               :ability-id ability-id
               :context (:context intent)
               :parameter-snapshot (:parameter-snapshot intent)
               :activation-seed (long (or (:activation-seed intent)
                                          (hash [owner ability-id])))
               :tick (long (or (:server-tick intent) 0))
               :start-tick (long (or (:server-tick intent) 0))
               :state {}
               :latches #{}}]
    (swap! sessions* assoc owner entry)
    entry))

(defn active? [owner]
  (contains? @sessions* owner))

(defn session [owner]
  (get @sessions* owner))

(defn remove! [owner]
  (swap! sessions* dissoc owner)
  nil)

(defn context-for [owner intent]
  (merge (select-keys (session owner)
                      [:ability-id :context :parameter-snapshot :activation-seed
                       :state :latches])
         (select-keys intent [:context :parameter-snapshot :activation-seed])))

(defn apply-actions!
  "Apply neutral session patches and latch claims after an accepted VM run.

  The operation is deliberately generic: paths and modes come from the
  compiled program, and this store contains no skill-specific keys or
  behavior."
  [owner actions]
  (let [patches (for [{:keys [type entries]} actions
                      :when (= :session-patch type)
                      entry entries]
                  entry)]
    (when (seq patches)
      (swap! sessions*
             update-in [owner :state]
             (fn [state]
               (reduce (fn [result {:keys [path mode value]}]
                         (case mode
                           :increment (update-in result path (fnil + 0.0)
                                                  (double (or value 0.0)))
                           :assign (assoc-in result path value)
                           result))
                       (or state {})
                       patches))))
    )
  (when-let [latches (some (fn [{:keys [type latches]}]
                             (when (= :session-latches type) latches)) actions)]
    (swap! sessions* update-in [owner :latches] into latches))
  nil)

(defn tick! [tick]
  (mapv (fn [[owner entry]]
          [owner (assoc entry :server-tick (long tick))])
        (swap! sessions*
               (fn [sessions]
                 (into {}
                       (map (fn [[owner entry]]
                              [owner (assoc entry :tick (long tick))]))
                       sessions)))))

(defn snapshot [] @sessions*)

(defn reset-for-test! []
  (reset! sessions* {})
  nil)

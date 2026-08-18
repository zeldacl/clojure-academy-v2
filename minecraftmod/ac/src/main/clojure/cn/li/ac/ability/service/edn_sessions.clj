(ns cn.li.ac.ability.service.edn-sessions
  "AC-owned session index for the generic EDN execution boundary.

  The session value contains only neutral owner/ability/context data.  It
  never stores Minecraft objects or skill-specific state; all behavior is
  still selected by the compiled EDN program."
  (:require [cn.li.ac.ability.service.edn-catalog :as catalog]))

(defonce ^:private sessions* (atom {}))

(defn start! [owner ability-id intent]
  (catalog/require-available ability-id)
  (let [entry {:owner owner
               :ability-id ability-id
               :context (:context intent)
               :parameter-snapshot (:parameter-snapshot intent)
               :activation-seed (long (or (:activation-seed intent)
                                          (hash [owner ability-id])))
               :tick (long (or (:server-tick intent) 0))}]
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
                      [:ability-id :context :parameter-snapshot :activation-seed])
         (select-keys intent [:context :parameter-snapshot :activation-seed])))

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

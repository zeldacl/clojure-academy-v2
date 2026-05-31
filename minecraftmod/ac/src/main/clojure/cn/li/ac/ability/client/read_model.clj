(ns cn.li.ac.ability.client.read-model
  "Client-side read model helpers for player-state projection access.

  Centralizes owner/session resolution and runtime-store reads so UI modules
  avoid depending on store wiring details."
  (:require [cn.li.ac.ability.service.runtime-store :as store]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(defn- require-owner-value
  [owner label value]
  (if (some? value)
    value
    (throw (ex-info (format "Client read model owner requires %s" label)
                    {:owner owner
                     :required label}))))

(defn owner-key
  "Normalize owner to [session-id screen-id player-uuid]."
  [owner screen-id]
  (let [owner-map (cond
                    (vector? owner) owner
                    (map? owner) owner
                    (some? owner) {:player-uuid owner}
                    :else {})]
    (if (vector? owner-map)
      owner-map
      [(require-owner-value owner ":client-session-id"
                            (or (:client-session-id owner-map)
                                (:session-id owner-map)
                                runtime-hooks/*client-session-id*))
       screen-id
       (require-owner-value owner ":player-uuid"
                            (some-> (or (:player-uuid owner-map)
                                        (:uuid owner-map))
                                    str))])))

(defn with-player-state-owner
  "Bind client owner context and run f as (f session-id player-uuid)."
  [owner-key f]
  (let [[session-id _screen-id player-uuid] owner-key]
    (binding [runtime-hooks/*client-session-id* session-id]
      (runtime-hooks/with-player-state-owner {:client-session-id session-id
                                              :player-uuid player-uuid}
        (f session-id player-uuid)))))

(defn get-player-state
  [owner-key]
  (with-player-state-owner owner-key
    (fn [session-id player-uuid]
      (store/get-player-state* session-id player-uuid))))

(defn ensure-player-state!
  [owner-key]
  (with-player-state-owner owner-key
    (fn [session-id player-uuid]
      (store/get-or-create-player-state! session-id player-uuid))))

(defn get-player-contexts
  "Read client-visible contexts from projected player-state context-registry."
  [owner-key]
  (let [[session-id _screen-id player-uuid] owner-key
        player-state (or (get-player-state owner-key) {})
        expected-session [session-id player-uuid]]
    (->> (or (:context-registry player-state) {})
         vals
         (filter map?)
         ;; Some projected context maps do not persist owner metadata.
         ;; Treat missing metadata as already owner-scoped by player-state.
         (filter #(or (nil? (:player-uuid %))
                      (= (some-> player-uuid str)
                         (some-> (:player-uuid %) str))))
         (filter #(or (nil? (:logical-side %))
                      (= :client (:logical-side %))))
         (filter #(or (nil? (:session-id %))
                      (= expected-session (:session-id %))))
         vec)))

(defn get-player-contexts-for-player
  "Read contexts for one player. Falls back to dispatcher query when client
  session is unavailable, preserving legacy client behavior."
  ([player-uuid]
   (vec (ctx/get-all-contexts-for-player (str player-uuid))))
  ([player-uuid session-id screen-id]
   (if session-id
     (let [player-uuid* (str player-uuid)
           owner {:logical-side :client
                  :session-id [session-id player-uuid*]}
           projected (get-player-contexts [session-id screen-id player-uuid*])]
       (if (seq projected)
         projected
         (vec (ctx/get-all-contexts-for-player owner player-uuid*))))
     (get-player-contexts-for-player player-uuid))))

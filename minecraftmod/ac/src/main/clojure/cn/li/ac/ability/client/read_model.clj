(ns cn.li.ac.ability.client.read-model
  "Client-side read model helpers for player-state projection access.

  Centralizes owner/session resolution and runtime-store reads so UI modules
  avoid depending on store wiring details."
  (:require [cn.li.ac.ability.service.runtime-store :as store]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.runtime.owner :as owner]))

(defn- require-owner-value
  [owner label value]
  (if (some? value)
    value
    (throw (ex-info (format "Client read model owner requires %s" label)
                    {:owner owner
                     :required label}))))

(defn owner-key
  "Normalize owner to [client-session-id screen-id player-uuid] for UI indexing."
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
                                ;; NB: client-session-id is a FUNCTION (hooks
                                ;; core 调用规范 #4) — must be invoked.
                                (runtime-hooks/client-session-id)))
       screen-id
       (require-owner-value owner ":player-uuid"
                            (some-> (or (:player-uuid owner-map)
                                        (:uuid owner-map))
                                    str))])))

(defn canonical-client-owner
  [owner screen-id]
  (let [[session-id _screen-id player-uuid] (owner-key owner screen-id)]
    {:logical-side :client
     :client-session-id session-id
     :player-uuid player-uuid}))

(defn with-player-state-owner
  "Bind client owner context and run f as (f session-id player-uuid)."
  [owner-key f]
  (let [[session-id _screen-id player-uuid] owner-key
        client-owner {:logical-side :client
                      :client-session-id session-id
                      :player-uuid player-uuid}]
    (runtime-hooks/with-client-ctx {:session-id session-id}
      (runtime-hooks/with-player-state-owner client-owner
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
  (let [[_session-id _screen-id player-uuid] owner-key
        player-state (or (get-player-state owner-key) {})]
    (->> (or (:context-registry player-state) {})
         vals
         (filter map?)
         (filter #(or (nil? (:player-uuid %))
                      (= (str player-uuid)
                         (some-> (:player-uuid %) str))))
         (filter #(or (nil? (:logical-side %))
                      (= :client (:logical-side %))))
         vec)))

(defn get-player-contexts-for-player
  "Read contexts for one player from projected player-state only."
  ([player-uuid]
   (if-let [session-id (runtime-hooks/client-session-id)]
     (get-player-contexts-for-player player-uuid session-id nil)
     []))
  ([player-uuid session-id screen-id]
   (if session-id
     (get-player-contexts [session-id screen-id (str player-uuid)])
     [])))

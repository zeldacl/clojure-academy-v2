(ns cn.li.ac.ability.client.read-model
  "Client-side read model helpers for player-state projection access.

  Centralizes owner/session resolution and runtime-store reads so UI modules
  avoid depending on store wiring details."
  (:require [cn.li.ac.ability.service.runtime-store :as store]
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

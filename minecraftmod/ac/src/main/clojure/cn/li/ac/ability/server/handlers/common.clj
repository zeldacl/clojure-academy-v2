(ns cn.li.ac.ability.server.handlers.common
  "Shared handler helpers for ability server network handlers."
  (:require [clojure.string :as str]
            [cn.li.ac.ability.service.command-runtime :as command-rt]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.runtime-store :as store]
            [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(defn current-server-session-id
  []
  (runtime-hooks/player-state-server-session-id))

(defn require-server-session-id
  [reason]
  (or (current-server-session-id)
      (runtime-hooks/require-player-state-server-session-id reason)))

(defn server-context-owner
  [player-uuid]
  (let [server-session-id (require-server-session-id "Server context owner")]
    {:logical-side :server
     :server-session-id server-session-id
     :session-id [server-session-id player-uuid]
     :player-uuid player-uuid}))

(defn- current-owner
  []
  (runtime-hooks/current-player-state-owner))

(defn get-state
  [player-uuid]
  (let [session-id (current-server-session-id)]
    (when-not session-id
      (throw (ex-info "Server handler state read requires :server-session-id/:session-id"
                      {:uuid player-uuid
                       :player-state-owner (current-owner)})))
    (store/get-or-create-player-state! session-id player-uuid)))

(defn valid-ctx-id?
  [ctx-id]
  (and (string? ctx-id) (not (str/blank? ctx-id))))

(defn resolve-owned-context
  [ctx-id player]
  (let [player-uuid (uuid/player-uuid player)
        owner (when player-uuid (server-context-owner player-uuid))
        ctx-map (when (and owner (valid-ctx-id? ctx-id))
                  (ctx/get-context owner ctx-id))]
    (cond
      (not (valid-ctx-id? ctx-id))
      {:ok? false :reason :payload-invalid}

      (nil? player-uuid)
      {:ok? false :reason :player-uuid-missing}

      (nil? ctx-map)
      {:ok? false :reason :ctx-not-found}

      (not= player-uuid (:player-uuid ctx-map))
      {:ok? false :reason :ctx-not-owner}

      :else
      {:ok? true :ctx ctx-map :owner owner})))

(defn resolve-owned-alive-context
  [ctx-id player]
  (let [{:keys [ok? ctx] :as result} (resolve-owned-context ctx-id player)]
    (cond
      (not ok?)
      result

      (not= (:status ctx) ctx/STATUS-ALIVE)
      {:ok? false :reason :ctx-not-alive}

      :else
      result)))

(defn sync-keepalive-command!
  [owner ctx-id]
  (let [server-session-id (:server-session-id owner)
        [_session-id player-uuid] (:session-id owner)]
    (when (and server-session-id player-uuid)
      (command-rt/run-command-in-session!
       server-session-id
       player-uuid
       {:command :touch-context-keepalive
        :ctx-id ctx-id
        :timestamp-ms (System/currentTimeMillis)}))))

(defn refresh-owned-alive-context!
  [ctx-id player]
  (let [{:keys [ok? owner]} (resolve-owned-alive-context ctx-id player)]
    (when ok?
      (sync-keepalive-command! owner ctx-id)
      owner)))


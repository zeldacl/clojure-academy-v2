(ns cn.li.ac.ability.service.command-runtime
  "Imperative shell bridge for reducer command execution.

  This namespace is the runtime entry for command-based state transitions:
  1) load current player state
  2) apply reducer command(s)
  3) persist resulting state
  4) execute emitted events/effects"
  (:require 
            [cn.li.ac.ability.application.contracts :as contracts]
            [cn.li.ac.ability.service.runtime-store :as store]
[cn.li.ac.ability.effects.interpreter :as interpreter]
            [cn.li.ac.ability.service.reducer :as reducer]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(defonce ^:private session-id-resolver*
  (atom (fn [] (runtime-hooks/player-state-session-id))))

(defonce ^:private owner-resolver*
  (atom (fn [] (runtime-hooks/current-player-state-owner))))

(defn install-session-runtime!
  "Install runtime callbacks used for implicit session/owner resolution.

  This is a migration bridge toward explicit dependency wiring.
  Keys:
  - :session-id-resolver (fn [] -> string|nil)
  - :owner-resolver      (fn [] -> owner-map|nil)"
  [{:keys [session-id-resolver owner-resolver]}]
  (when session-id-resolver
    (reset! session-id-resolver* session-id-resolver))
  (when owner-resolver
    (reset! owner-resolver* owner-resolver))
  nil)

(def ^:private default-command-trace-ttl-ms 60000)
(def ^:private default-max-command-traces 2048)

(defonce ^:private recent-command-traces* (atom {}))

(defn- now-ms
  []
  (System/currentTimeMillis))

(defn- trace-key
  [session-id uuid command-id]
  [session-id uuid command-id])

(defn- prune-command-traces
  [snapshot now ttl-ms max-size]
  (let [fresh (into {}
                    (filter (fn [[_ {:keys [completed-at-ms]}]]
                              (<= (- now (long completed-at-ms)) ttl-ms))
                            snapshot))]
    (if (<= (count fresh) max-size)
      fresh
      (let [to-drop (- (count fresh) max-size)
            sorted-keys (->> fresh
                             (sort-by (fn [[_ {:keys [completed-at-ms]}]]
                                        (long completed-at-ms)))
                             (map first))]
        (reduce (fn [m k] (dissoc m k))
                fresh
                (take to-drop sorted-keys))))))

(defn- lookup-command-trace
  [session-id uuid command-id]
  (when command-id
    (get @recent-command-traces* (trace-key session-id uuid command-id))))

(defn- record-command-trace!
  [session-id uuid command-id result]
  (when command-id
    (let [at (now-ms)
          ttl-ms default-command-trace-ttl-ms
          max-size default-max-command-traces]
      (swap! recent-command-traces*
             (fn [snapshot]
               (-> snapshot
                   (assoc (trace-key session-id uuid command-id)
                          {:completed-at-ms at
                           :result result})
                   (prune-command-traces at ttl-ms max-size))))))
  nil)

(defn reset-command-traces-for-test!
  []
  (reset! recent-command-traces* {})
  nil)

(defn command-traces-snapshot
  []
  @recent-command-traces*)

(defn- ensure-command-owner-fields
  [session-id uuid command]
  (-> command
      contracts/trim-command-meta
      (update :command-id #(or % (str (java.util.UUID/randomUUID))))
      (assoc :uuid uuid)
      (assoc :player-uuid uuid)
      (cond->
        session-id (assoc :session-id session-id))))

(defn- resolve-session-id
  [session-id]
  (or session-id
      ((or @session-id-resolver* (fn [] nil)))
      (throw (ex-info "Command runtime requires explicit/bound session-id"
                      {:provided-session-id session-id
                       :player-state-owner ((or @owner-resolver* (fn [] nil)))}))))

(defn- run-command*
  [session-id uuid command {:keys [mark-dirty?]
                            :or {mark-dirty? true}
                            :as opts}]
  (contracts/assert-command! command)
  (let [session-id (resolve-session-id session-id)
        normalized (ensure-command-owner-fields session-id uuid command)
        command-id (:command-id normalized)
        cached (when (:idempotent? opts)
                 (some-> (lookup-command-trace session-id uuid command-id)
                         :result))]
    (if cached
      (assoc cached :idempotent-replay? true)
      (let [state (store/get-or-create-player-state! session-id uuid)
            result (reducer/apply-command state normalized)
            _ (contracts/assert-reducer-result! result)
            next-state (:state result)]
        (when (and next-state (not= next-state state))
          (store/set-player-state!* session-id uuid next-state)
          (when mark-dirty?
            (store/mark-player-dirty! session-id uuid)))
        (interpreter/execute-reducer-result! result)
        (record-command-trace! session-id uuid command-id result)
        result))))

(defn run-command-in-session!
  "Execute one reducer command with an explicit runtime session id.

  This API is the migration target for removing implicit owner resolution."
  ([session-id uuid command]
   (run-command-in-session! session-id uuid command {}))
  ([session-id uuid command opts]
   (run-command* session-id uuid command opts)))

(defn- run-commands*
  [session-id uuid commands {:keys [mark-dirty?]
                             :or {mark-dirty? true}}]
  (doseq [command commands]
    (contracts/assert-command! command))
  (let [session-id (resolve-session-id session-id)
        state (store/get-or-create-player-state! session-id uuid)
        normalized (mapv #(ensure-command-owner-fields session-id uuid %) commands)
        result (reducer/apply-commands state normalized)
        _ (contracts/assert-reducer-result! result)
        next-state (:state result)]
    (when (and next-state (not= next-state state))
      (store/set-player-state!* session-id uuid next-state)
      (when mark-dirty?
        (store/mark-player-dirty! session-id uuid)))
    (interpreter/execute-reducer-result! result)
    result))

(defn run-commands-in-session!
  "Execute multiple reducer commands with an explicit runtime session id."
  ([session-id uuid commands]
   (run-commands-in-session! session-id uuid commands {}))
  ([session-id uuid commands opts]
   (run-commands* session-id uuid commands opts)))




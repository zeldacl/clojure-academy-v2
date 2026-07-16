(ns cn.li.ac.ability.service.context-manager
  "Context lifecycle manager (server-side).

  Responsibilities:
  - activate-context!  : client requests to start a skill; creates and sends BEGIN-LINK
  - abort-player-contexts! : terminates all of a player's live contexts (death, category change)
  - tick-context-manager! : keepalive timeout sweep

  Transport in context-dispatcher; business fields in runtime-store [:context-registry].
  Network send-fns are injected, keeping this ns free of forge deps."
  (:require [cn.li.ac.ability.service.runtime-store :as store]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.context-transport :as transport]
            [cn.li.ac.ability.service.context-state :as ctx-state]
            [cn.li.ac.ability.service.command-runtime :as command-rt]
            [cn.li.ac.ability.service.context-domain :as context-domain]
            [cn.li.ac.ability.domain.skill :as skill-domain]
            [cn.li.ac.ability.registry.skill :as skill-registry]
            [cn.li.ac.ability.model.ability :as adata]
            [cn.li.ac.ability.model.resource :as rdata]
            [cn.li.ac.ability.messages :as catalog]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.runtime.owner :as owner]
            [cn.li.mcmod.util.log :as log]))

(def ^:private configured-terminated-context-grace-ms 1000)
(def ^:private configured-keepalive-timeout-ms 1500)

(defn keepalive-timeout-ms
  "Server-side keepalive timeout threshold in milliseconds.
  Frozen at runtime bootstrap; hot sweeps never read system properties."
  []
  configured-keepalive-timeout-ms)

(defn terminated-context-grace-ms
  "Grace window before terminated contexts are purged from registry.
  Frozen at runtime bootstrap."
  []
  configured-terminated-context-grace-ms)

(defn- resolve-server-session-id
  [reason]
  (runtime-hooks/require-player-state-server-session-id reason))

(defn register-send-fns! [{:keys [to-client to-server]}]
  (transport/register-send-fns! {:to-client to-client :to-server to-server}))

(defn- send-terminated! [ctx-id]
  (when-let [ctx-map (ctx/get-context ctx-id)]
    (let [player-uuid (:player-uuid ctx-map)]
      (transport/send-to-client! player-uuid catalog/MSG-CTX-TERMINATE {:ctx-id ctx-id}))))

(defn- server-context-owner
  [player-uuid]
  (let [server-session-id (resolve-server-session-id "Server context owner")]
    {:logical-side :server
     :server-session-id server-session-id
     :player-uuid (str player-uuid)}))

(defn- server-player-state
  [player-uuid]
  (let [session-id (resolve-server-session-id "Server context state read")]
    (store/get-player-state* session-id player-uuid)))

(defn- active-server-contexts-for-player
  [owner player-uuid]
  (let [pid (str player-uuid)
        contexts (ctx/transport-contexts-for-player owner player-uuid)
        size (count contexts)]
    (loop [index 0
           matches (transient [])]
      (if (< index size)
        (let [ctx-map (nth contexts index)]
          (recur (unchecked-inc-int index)
                 (if (and (= :server (:logical-side ctx-map))
                          (= pid (:player-uuid ctx-map))
                          (= ctx/STATUS-ALIVE (:status ctx-map))
                          (= ctx-state/INPUT-ACTIVE (:input-state ctx-map)))
                   (conj! matches ctx-map)
                   matches)))
        (persistent! matches)))))

(defn activate-context!
  "Called on the CLIENT when player triggers a skill (e.g., key press).
  Creates a CONSTRUCTED context, registers it, and sends BEGIN-LINK to server."
  ([player-uuid skill-id]
   (activate-context! nil player-uuid skill-id))
  ([owner player-uuid skill-id]
   (let [new-ctx (if owner
                   (ctx/new-context player-uuid skill-id owner)
                   (ctx/new-context player-uuid skill-id))]
     (ctx/register-context! new-ctx)
     (ctx/with-context-owner owner
       (transport/send-to-server! catalog/MSG-CTX-BEGIN-LINK
                                  {:ctx-id (:id new-ctx)
                                   :skill-id skill-id}))
     (log/debug "Context activated (client):" (:id new-ctx) skill-id)
     new-ctx)))

(defn can-establish-skill-context?
  "Return true when a learned skill is enabled, controllable, and usable now."
  [ability-data resource-data skill-id]
  (boolean
   (and ability-data
        resource-data
        (adata/is-learned? ability-data skill-id)
        (rdata/can-use-ability? resource-data)
        (skill-domain/controllable? (skill-registry/get-skill skill-id)))))

(defn establish-context!
  "Called on the SERVER upon receiving MSG-CTX-BEGIN-LINK.
  Validates the skill for the player, creates server-side context, sends ESTABLISH."
  [player-uuid client-ctx-id skill-id]
  (let [owner (server-context-owner player-uuid)
        state (server-player-state player-uuid)
        ad (:ability-data state)
        rd (:resource-data state)]
    (if-not (and ad rd)
      (log/warn "establish-context!: no player state for" player-uuid)
      (if-not (can-establish-skill-context? ad rd skill-id)
        (do
          (log/debug "establish-context! rejected: conditions not met" player-uuid skill-id)
          (transport/send-to-client! player-uuid catalog/MSG-CTX-TERMINATE {:ctx-id client-ctx-id})
          nil)
        (let [server-ctx (ctx/new-server-context player-uuid skill-id client-ctx-id owner)]
          (ctx/register-context! server-ctx)
          (transport/send-to-client! player-uuid catalog/MSG-CTX-ESTABLISH
                                     {:ctx-id client-ctx-id
                                      :server-id (:server-id server-ctx)})
          (log/debug "Context established (server):" (:server-id server-ctx) skill-id)
          server-ctx)))))

(defn abort-player-contexts!
  "Terminate all active contexts for a player (death, category change, logoff).
  Should be called from forge player event handlers."
  [player-uuid]
  (let [owner (server-context-owner player-uuid)]
    (ctx/abort-all-contexts-for-player! owner
                                            player-uuid
                                            send-terminated!)))

(defn send-terminated-context!
  "Notify client that a specific context has terminated."
  [ctx-id]
  (send-terminated! ctx-id))

(defn- context-owner-from-map
  [ctx-map]
  (or (owner/canonical-owner-from-transport ctx-map)
      (when-let [ctx-id (:id ctx-map)]
        (some->> (ctx/snapshot-transport-contexts)
                (filter #(= ctx-id (:id %)))
                first
                owner/canonical-owner-from-transport))
      (when-let [side (:logical-side ctx-map)]
        (cond-> {:logical-side side}
          (:player-uuid ctx-map) (assoc :player-uuid (str (:player-uuid ctx-map)))))))

(defn- expired-server-context?
  [now timeout-ms ctx-map]
  (and (= :server (:logical-side ctx-map))
       (= ctx/STATUS-ALIVE (:status ctx-map))
       (:last-keepalive-ms ctx-map)
       (> (- now (:last-keepalive-ms ctx-map)) timeout-ms)))

(defn- stale-terminated-context?
  [now grace-ms ctx-map]
  (and (= ctx/STATUS-TERMINATED (:status ctx-map))
       (:terminated-at-ms ctx-map)
       (>= (- now (:terminated-at-ms ctx-map)) grace-ms)))

(defn- sweep-contexts!
  "Single pass over the transport-context snapshot doing both the keepalive
  timeout sweep and the stale-terminated purge (previously two independent
  full snapshot+filter passes)."
  []
  (let [now (System/currentTimeMillis)
        timeout-ms (keepalive-timeout-ms)
        grace-ms (terminated-context-grace-ms)]
    (doseq [ctx-map (ctx/snapshot-transport-contexts)]
      (cond
        (expired-server-context? now timeout-ms ctx-map)
        (ctx/terminate-context! (context-owner-from-map ctx-map) (:id ctx-map) send-terminated!)

        (stale-terminated-context? now grace-ms ctx-map)
        (ctx/remove-context! (context-owner-from-map ctx-map) (:id ctx-map))))))

(defn push-channel-to-player!
  "Push a context channel payload to a player's client.

  This is used by delayed server-side tasks where the original context may
  already be terminated, but client FX still needs to be dispatched."
  [player-uuid ctx-id channel payload]
  (transport/send-to-client! player-uuid
                             catalog/MSG-CTX-CHANNEL
                             {:ctx-id ctx-id
                              :channel channel
                              :payload payload}))

(defn push-channel-to-nearby-players!
  "Push a context channel payload to nearby players except the local owner.

  Uses dispatcher route `:to-except-local` when the context route is still
  available, which mirrors normal server-side FX fan-out behavior."
  [ctx-id channel payload]
  (ctx/ctx-send-to-except-local! ctx-id channel payload)
  nil)

(defn- tick-context-entry!
  "Drive a single server-owned context for one tick.
   Extracted to top-level defn- so AOT emits exactly one static class —
   zero per-tick closure capture, zero JIT class generation.
   The payload map is created inside this static function where JVM
   escape analysis can cheaply stack-allocate or aggressively reclaim it."
  [owner callback {:keys [id skill-id]}]
  (ctx-state/handle-key-tick! owner id {:ctx-id id
                                        :skill-id skill-id}
                              callback))

(defn tick-player-contexts!
  "Drive all active server-owned contexts for one player once per server tick.
  Early-exits when the player has no context-registry entries at all — the
  common case for idle players — avoiding the global registry snapshot,
  owner map allocation, and :purge-terminated-contexts command dispatch."
  [player-uuid]
  (when (seq (:context-registry (server-player-state player-uuid)))
    (let [owner (server-context-owner player-uuid)]
      (doseq [spec (active-server-contexts-for-player owner player-uuid)]
        (tick-context-entry! owner send-terminated-context! spec))
      (when-let [server-session-id (owner/store-session-id owner)]
        (command-rt/run-command-in-session!
         server-session-id
         player-uuid
         {:command :purge-terminated-contexts})))))

(defn tick-context-manager!
  "Run the global keepalive/purge sweep once from server-tick-start!."
  []
  (sweep-contexts!))

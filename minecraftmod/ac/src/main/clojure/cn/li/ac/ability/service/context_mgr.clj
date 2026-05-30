(ns cn.li.ac.ability.service.context-mgr
  "Context lifecycle manager (server-side).

  Responsibilities:
  - activate-context!  : client requests to start a skill; creates and sends BEGIN-LINK
  - abort-player-contexts! : terminates all of a player's live contexts (death, category change)
  - tick-context-manager! : keepalive timeout sweep

  All state is in context/context-registry (owned by context.clj).
  Network send-fns are injected, keeping this ns free of forge deps."
  (:require 
            [cn.li.ac.ability.service.player-state-core :as ps-core]
[cn.li.ac.ability.service.dispatcher :as ctx]            [cn.li.ac.ability.service.context-transport :as transport]
            [cn.li.ac.ability.service.context-runtime :as ctx-rt]
            [cn.li.ac.ability.domain.skill :as skill-domain]
            [cn.li.ac.ability.registry.skill :as skill-registry]
            [cn.li.ac.ability.model.ability :as adata]
            [cn.li.ac.ability.model.resource :as rdata]
            [cn.li.ac.ability.messages :as catalog]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.util.log :as log]))

(defn register-send-fns! [{:keys [to-client to-server]}]
  (transport/register-send-fns! {:to-client to-client :to-server to-server}))

(defn- send-terminated! [ctx-id]
  (when-let [ctx (ctx/get-context ctx-id)]
    (let [player-uuid (:player-uuid ctx)]
      (transport/send-to-client! player-uuid catalog/MSG-CTX-TERMINATE {:ctx-id ctx-id}))))

(defn- server-context-owner
  [player-uuid]
  (let [owner runtime-hooks/*player-state-owner*
        server-session-id (:server-session-id owner)]
    (when-not server-session-id
      (throw (ex-info "Server context owner requires bound :server-session-id"
                      {:player-uuid player-uuid
                       :player-state-owner owner})))
    {:logical-side :server
     :server-session-id server-session-id
     :session-id [server-session-id player-uuid]
     :player-uuid player-uuid}))

(defn- active-server-contexts-for-player
  [player-uuid]
  (let [owner (server-context-owner player-uuid)]
    (->> (ctx/snapshot-context-registry)
         vals
         (filter (fn [ctx-map]
                   (and (= :server (:logical-side ctx-map))
                        (= (:session-id owner) (:session-id ctx-map))
                        (= player-uuid (:player-uuid ctx-map))
                        (= ctx/STATUS-ALIVE (:status ctx-map))
                        (= ctx-rt/INPUT-ACTIVE (:input-state ctx-map)))))
         (sort-by (juxt :id :server-id)))))

(defn activate-context!
  "Called on the CLIENT when player triggers a skill (e.g., key press).
  Creates a CONSTRUCTED context, registers it, and sends BEGIN-LINK to server."
  [player-uuid skill-id]
  (let [new-ctx (ctx/new-context player-uuid skill-id)]
    (ctx/register-context! new-ctx)
    (transport/send-to-server! catalog/MSG-CTX-BEGIN-LINK
                               {:ctx-id (:id new-ctx)
                                :skill-id skill-id})
    (log/debug "Context activated (client):" (:id new-ctx) skill-id)
    new-ctx))

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
  (let [state (ps-core/get-player-state player-uuid)
        ad (:ability-data state)
        rd (:resource-data state)]
    (if-not (and ad rd)
      (log/warn "establish-context!: no player state for" player-uuid)
      (if-not (can-establish-skill-context? ad rd skill-id)
        (do
          (log/debug "establish-context! rejected: conditions not met" player-uuid skill-id)
          (transport/send-to-client! player-uuid catalog/MSG-CTX-TERMINATE {:ctx-id client-ctx-id})
          nil)
        (let [server-ctx (ctx/new-server-context player-uuid skill-id client-ctx-id)]
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
    (binding [ctx/*context-owner* owner]
      (ctx/abort-all-contexts-for-player! owner
                                          player-uuid
                                          send-terminated!))))

(defn send-terminated-context!
  "Notify client that a specific context has terminated."
  [ctx-id]
  (send-terminated! ctx-id))

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

(defn tick-player-contexts!
  "Drive all active server-owned contexts for one player once per server tick."
  [player-uuid]
  (let [owner (server-context-owner player-uuid)]
    (doseq [{:keys [id skill-id]} (active-server-contexts-for-player player-uuid)]
      (binding [ctx/*context-owner* owner]
        (ctx-rt/handle-key-tick! id {:ctx-id id
                                     :skill-id skill-id}
                                 send-terminated-context!)))))

(defn tick-context-manager!
  "Call once per second (or per server tick with rate limiting).
  Checks keepalive timeouts on all live ALIVE contexts."
  []
  (ctx/check-keepalive-timeout! send-terminated!)
  (ctx/purge-terminated-contexts!))


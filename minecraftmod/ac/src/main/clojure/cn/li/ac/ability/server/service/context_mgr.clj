(ns cn.li.ac.ability.server.service.context-mgr
  "Context lifecycle manager (server-side).

  Responsibilities:
  - activate-context!  : client requests to start a skill; creates and sends BEGIN-LINK
  - establish-context! : server receives BEGIN-LINK, creates server-side context and replies ESTABLISH
  - abort-player-contexts! : terminates all of a player's live contexts (death, category change)
  - tick-context-manager! : keepalive timeout sweep

  All state is in context/context-registry (owned by context.clj).
  Network send-fns are injected, keeping this ns free of forge deps."
  (:require [cn.li.ac.ability.state.context :as ctx]
            [cn.li.ac.ability.registry.event :as evt]
            [cn.li.ac.ability.registry.skill :as skill]
            [cn.li.ac.ability.state.player :as ps]
            [cn.li.ac.ability.model.resource :as rdata]
            [cn.li.mcmod.ability.catalog :as catalog]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; send-fn registry (injected at forge layer startup)
;; ============================================================================

(defonce ^:private send-to-client-fn (atom nil))
(defonce ^:private send-to-server-fn (atom nil))

(defn register-send-fns! [{:keys [to-client to-server]}]
  (reset! send-to-client-fn to-client)
  (reset! send-to-server-fn to-server))

;; ============================================================================
;; Internal helpers
;; ============================================================================

(defn- send-to-client! [player-uuid msg-id payload]
  (when-let [f @send-to-client-fn]
    (f player-uuid msg-id payload)))

(defn- send-to-server! [msg-id payload]
  (when-let [f @send-to-server-fn]
    (f msg-id payload)))

(defn- send-terminated! [ctx-id]
  (when-let [ctx (ctx/get-context ctx-id)]
    (let [player-uuid (:player-uuid ctx)]
      (send-to-client! player-uuid catalog/MSG-CTX-TERMINATE {:ctx-id ctx-id}))))

;; ============================================================================
;; Client-side: request to start a skill
;; ============================================================================

(defn activate-context!
  "Called on the CLIENT when player triggers a skill (e.g., key press).
  Creates a CONSTRUCTED context, registers it, and sends BEGIN-LINK to server."
  [player-uuid skill-id]
  (let [new-ctx (ctx/new-context player-uuid skill-id)]
    (ctx/register-context! new-ctx)
    (send-to-server! catalog/MSG-CTX-BEGIN-LINK
                     {:ctx-id   (:id new-ctx)
                      :skill-id skill-id})
    (log/debug "Context activated (client):" (:id new-ctx) skill-id)
    new-ctx))

;; ============================================================================
;; Server-side: respond to BEGIN-LINK
;; ============================================================================

(defn establish-context!
  "Called on the SERVER upon receiving MSG-CTX-BEGIN-LINK.
  Validates the skill for the player, creates server-side context, sends ESTABLISH."
  [player-uuid client-ctx-id skill-id]
  (let [state     (ps/get-player-state player-uuid)
        ad        (:ability-data state)
        rd        (:resource-data state)]
    (if-not (and ad rd)
      (log/warn "establish-context!: no player state for" player-uuid)
      ;; Basic check: skill is learned and ability usable
      (if-not (and (get-in ad [:learned-skills skill-id])
                   (rdata/can-use-ability? rd))
        (do
          (log/debug "establish-context! rejected: conditions not met" player-uuid skill-id)
          (send-to-client! player-uuid catalog/MSG-CTX-TERMINATE {:ctx-id client-ctx-id}))
        (let [server-ctx (ctx/new-server-context player-uuid skill-id client-ctx-id)]
          (ctx/register-context! server-ctx)
          (send-to-client! player-uuid catalog/MSG-CTX-ESTABLISH
                           {:ctx-id    client-ctx-id
                            :server-id (:server-id server-ctx)})
          (log/debug "Context established (server):" (:server-id server-ctx) skill-id)
          server-ctx)))))

;; ============================================================================
;; Terminate helpers
;; ============================================================================

(defn abort-player-contexts!
  "Terminate all active contexts for a player (death, category change, logoff).
  Should be called from forge player event handlers."
  [player-uuid]
  (ctx/abort-all-contexts-for-player! player-uuid send-terminated!))

(defn send-terminated-context!
  "Notify client that a specific context has terminated."
  [ctx-id]
  (send-terminated! ctx-id))

;; ============================================================================
;; Server tick
;; ============================================================================

(defn tick-context-manager!
  "Call once per second (or per server tick with rate limiting).
  Checks keepalive timeouts on all live ALIVE contexts."
  []
  (ctx/check-keepalive-timeout! send-terminated!))

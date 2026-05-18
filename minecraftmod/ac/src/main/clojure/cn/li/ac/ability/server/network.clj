(ns cn.li.ac.ability.server.network
  "Server-side message handler registrations for the ability system.

  All handlers registered here correspond to MSG-* constants in catalog.clj.
  Incoming messages carry a payload map and a player-uuid string.

  All mutating calls go through player-state ns; no atom touched directly.
  No net.minecraft.* imports allowed."
  (:require [cn.li.mcmod.network.server         :as net-srv]
            [cn.li.mcmod.hooks.catalog          :as catalog]
            [cn.li.mcmod.platform.entity        :as entity]
            [cn.li.ac.ability.service.player-state      :as ps]
            [cn.li.ac.ability.model.ability :as adata]
            [cn.li.ac.ability.server.service.learning  :as lrn]
            [cn.li.ac.ability.service.registry             :as skill]
            [cn.li.ac.ability.server.handlers.level-handler :as level-handler]
            [cn.li.ac.ability.server.handlers.preset-handler :as preset-handler]
            [cn.li.ac.ability.server.handlers.activation-handler :as activation-handler]
            [cn.li.ac.ability.server.handlers.context-handler :as context-handler]
            [cn.li.ac.ability.server.handlers.input-handler :as input-handler]
            [cn.li.ac.ability.server.util.developer-validation :as dev-validate]
            [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.ac.wireless.gui.sync.handler :as net-helpers]
            [cn.li.mcmod.platform.world         :as world]
            [cn.li.mcmod.platform.be            :as platform-be]
            [cn.li.ac.ability.registry.event             :as evt]
            [cn.li.mcmod.util.log               :as log]))

  ;; ============================================================================
  ;; Helpers
  ;; ============================================================================

  (defn- get-state [uuid]
    (ps/get-or-create-player-state! uuid))

  (defn- try-pull-developer-energy!
    [tile ^double amount]
    (if-let [f (requiring-resolve 'cn.li.ac.block.developer.logic/try-pull-energy!)]
      (boolean (f tile amount))
      false))

  ;; ============================================================================
  ;; Skill learning
  ;; ============================================================================

  (defn- handle-learn-skill-request
    [payload player]
    (let [{:keys [skill-id pos-x pos-y pos-z]} payload
          uuid (uuid/player-uuid player)
          state (get-state uuid)
          ad (:ability-data state)
          player-level (:level ad)
          world (entity/player-get-level player)
          all-coords? (and (number? pos-x) (number? pos-y) (number? pos-z))
          tile (when (and all-coords? world)
                 (net-helpers/get-tile-at world
                   {:pos-x (long pos-x) :pos-y (long pos-y) :pos-z (long pos-z)}))
          st (when tile (or (platform-be/get-custom-state tile) {}))
          session-ok? (= (str (:user-uuid st "")) uuid)
          server-world? (and world (not (world/world-is-client-side* world)))
          station
          (when all-coords?
            (cond (not server-world?) {:ok? false :reason :not-server}
                  (not tile) {:ok? false :reason :no-tile}
                  (not (dev-validate/developer-controller-tile? tile)) {:ok? false :reason :wrong-block}
                  (not (dev-validate/dist-sq-ok-for-station? player tile)) {:ok? false :reason :distance}
                  (not session-ok?) {:ok? false :reason :session}
                  (not (:structure-valid st)) {:ok? false :reason :structure}
                  :else {:ok? true :tile tile :developer-type (dev-validate/developer-type-for-tile tile)}))
          do-learn!
          (fn []
            (let [{:keys [data event]} (lrn/learn-skill ad uuid skill-id)]
              (ps/update-ability-data! uuid (constantly data))
              (when event (evt/fire-ability-event! event))))]
      (when-not (adata/is-learned? ad skill-id)
        (cond
          (and all-coords? (not (:ok? station)))
          (log/debug "learn-skill rejected (station)" uuid skill-id (:reason station))

          all-coords?
          (let [dev-t (:developer-type station)
                {:keys [pass? failures]} (lrn/check-all-conditions skill-id ad player-level dev-t)]
            (if pass?
              (let [sk (skill/get-skill skill-id)
                    cost (double (skill/learning-cost (long (:level sk))))]
                (if (try-pull-developer-energy! (:tile station) cost)
                  (do-learn!)
                  (log/debug "learn-skill rejected (IF)" uuid skill-id cost)))
              (log/debug "learn-skill rejected" uuid skill-id failures)))

          :else
          (let [{:keys [pass? failures]} (lrn/check-all-conditions skill-id ad player-level :normal)]
            (if pass?
              (do-learn!)
              (log/debug "learn-skill rejected" uuid skill-id failures)))))))

  ;; ============================================================================
  ;; Delegated handlers (split from this namespace)
  ;; ============================================================================

  (def handle-level-up-request level-handler/handle-level-up-request)
  (def handle-set-preset-request preset-handler/handle-set-preset-request)
  (def handle-switch-preset-request preset-handler/handle-switch-preset-request)
  (def handle-set-activated-request activation-handler/handle-set-activated-request)

  (def handle-begin-link-context context-handler/handle-begin-link-context)
  (def handle-keepalive-context context-handler/handle-keepalive-context)
  (def handle-terminate-context context-handler/handle-terminate-context)
  (def handle-channel-context context-handler/handle-channel-context)

  (def handle-key-down-skill input-handler/handle-key-down-skill)
  (def handle-key-tick-skill input-handler/handle-key-tick-skill)
  (def handle-key-up-skill input-handler/handle-key-up-skill)
  (def handle-key-abort-skill input-handler/handle-key-abort-skill)

  ;; ============================================================================
  ;; ============================================================================
  ;; Registration
  ;; ============================================================================

(defn register-handlers! []
  (net-srv/register-handler catalog/MSG-REQ-LEARN-NODE     handle-learn-skill-request)
  (net-srv/register-handler catalog/MSG-REQ-LEVEL-UP       handle-level-up-request)
  (net-srv/register-handler catalog/MSG-REQ-SET-PRESET     handle-set-preset-request)
  (net-srv/register-handler catalog/MSG-REQ-SWITCH-PRESET  handle-switch-preset-request)
  (net-srv/register-handler catalog/MSG-REQ-SET-ACTIVATED  handle-set-activated-request)
  (net-srv/register-handler catalog/MSG-CTX-BEGIN-LINK     handle-begin-link-context)
  (net-srv/register-handler catalog/MSG-CTX-KEEPALIVE      handle-keepalive-context)
  (net-srv/register-handler catalog/MSG-CTX-TERMINATE      handle-terminate-context)
  (net-srv/register-handler catalog/MSG-CTX-CHANNEL        handle-channel-context)
  (net-srv/register-handler catalog/MSG-SLOT-KEY-DOWN      handle-key-down-skill)
  (net-srv/register-handler catalog/MSG-SLOT-KEY-TICK      handle-key-tick-skill)
  (net-srv/register-handler catalog/MSG-SLOT-KEY-UP        handle-key-up-skill)
  (net-srv/register-handler catalog/MSG-SLOT-KEY-ABORT     handle-key-abort-skill)
  (log/info "Ability network handlers registered"))

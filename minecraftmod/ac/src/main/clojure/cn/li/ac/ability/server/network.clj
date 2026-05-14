(ns cn.li.ac.ability.server.network
  "Server-side message handler registrations for the ability system.

  All handlers registered here correspond to MSG-* constants in catalog.clj.
  Incoming messages carry a payload map and a player-uuid string.

  All mutating calls go through player-state ns; no atom touched directly.
  No net.minecraft.* imports allowed."
  (:require [cn.li.mcmod.network.server         :as net-srv]
            [cn.li.mcmod.ability.catalog        :as catalog]
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
            [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.ac.wireless.gui.sync.handler :as net-helpers]
            [cn.li.ac.block.developer.logic     :as dev-logic]
            [cn.li.mcmod.platform.position      :as pos]
            [cn.li.mcmod.platform.world         :as world]
            [cn.li.mcmod.platform.be            :as platform-be]
            [cn.li.ac.ability.registry.event             :as evt]
            [cn.li.mcmod.util.log               :as log]))

  ;; ============================================================================
  ;; Helpers
  ;; ============================================================================

  (defn- get-state [uuid]
    (ps/get-or-create-player-state! uuid))

  (defn- developer-type-for-tile
    [tile]
    (let [bid (platform-be/get-block-id tile)
          n (name (or bid ""))]
      (if (= n "developer-advanced")
        :advanced
        :normal)))

  (defn- dist-sq-ok-for-station?
    [player tile]
    (let [raw-pos (try (pos/position-get-block-pos tile) (catch Exception _ nil))
          max-distance 8.0]
      (boolean
        (when raw-pos
          (let [bx (+ 0.5 (double (or (try (pos/pos-x raw-pos) (catch Exception _ nil))
                                      (:x raw-pos))))
                by (+ 0.5 (double (or (try (pos/pos-y raw-pos) (catch Exception _ nil))
                                      (:y raw-pos))))
                bz (+ 0.5 (double (or (try (pos/pos-z raw-pos) (catch Exception _ nil))
                                      (:z raw-pos))))]
            (< (entity/entity-distance-to-sqr player bx by bz)
               (* max-distance max-distance)))))))

  (defn- developer-controller-tile?
    [tile]
    (let [n (name (or (platform-be/get-block-id tile) ""))]
      (contains? #{"developer-normal" "developer-advanced"} n)))

  ;; ============================================================================
  ;; Skill learning
  ;; ============================================================================

  (defn- handle-learn-skill-request
    [payload player]
    (let [{:keys [skill-id pos-x pos-y pos-z]} payload
          uuid (uuid/player-uuid-str player)
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
                  (not (developer-controller-tile? tile)) {:ok? false :reason :wrong-block}
                  (not (dist-sq-ok-for-station? player tile)) {:ok? false :reason :distance}
                  (not session-ok?) {:ok? false :reason :session}
                  (not (:structure-valid st)) {:ok? false :reason :structure}
                  :else {:ok? true :tile tile :developer-type (developer-type-for-tile tile)}))
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
                (if (dev-logic/try-pull-energy! (:tile station) cost)
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
  (net-srv/register-handler catalog/MSG-REQ-LEARN-SKILL    handle-learn-skill-request)
  (net-srv/register-handler catalog/MSG-REQ-LEVEL-UP       handle-level-up-request)
  (net-srv/register-handler catalog/MSG-REQ-SET-PRESET     handle-set-preset-request)
  (net-srv/register-handler catalog/MSG-REQ-SWITCH-PRESET  handle-switch-preset-request)
  (net-srv/register-handler catalog/MSG-REQ-SET-ACTIVATED  handle-set-activated-request)
  (net-srv/register-handler catalog/MSG-CTX-BEGIN-LINK     handle-begin-link-context)
  (net-srv/register-handler catalog/MSG-CTX-KEEPALIVE      handle-keepalive-context)
  (net-srv/register-handler catalog/MSG-CTX-TERMINATE      handle-terminate-context)
  (net-srv/register-handler catalog/MSG-CTX-CHANNEL        handle-channel-context)
  (net-srv/register-handler catalog/MSG-SKILL-KEY-DOWN     handle-key-down-skill)
  (net-srv/register-handler catalog/MSG-SKILL-KEY-TICK     handle-key-tick-skill)
  (net-srv/register-handler catalog/MSG-SKILL-KEY-UP       handle-key-up-skill)
  (net-srv/register-handler catalog/MSG-SKILL-KEY-ABORT    handle-key-abort-skill)
  (log/info "Ability network handlers registered"))

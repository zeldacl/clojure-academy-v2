(ns cn.li.ac.ability.server.network
  "Server-side message handler registrations for the ability system.

  All handlers registered here correspond to MSG-* constants in catalog.clj.
  Incoming messages carry a payload map and a player-uuid string.

  All mutating calls go through player-state ns; no atom touched directly.
  No net.minecraft.* imports allowed."
  (:require 
[cn.li.mcmod.network.server         :as net-srv]
            [cn.li.ac.ability.messages          :as catalog]
            [cn.li.mcmod.platform.entity        :as entity]            [cn.li.ac.ability.model.ability :as adata]
            [cn.li.ac.ability.rules.learning-rules :as learning-rules]
            [cn.li.ac.ability.service.command-runtime :as command-rt]
            [cn.li.ac.ability.service.combat-runtime :as combat-runtime]
            [cn.li.ac.ability.service.combat-catalog :as combat-catalog]
            [cn.li.combat.vfx-publish :as vfx-publish]
            [cn.li.ac.ability.registry.skill             :as skill]
            [cn.li.ac.ability.rules.progression          :as progression]
            [cn.li.ac.ability.server.handlers.level-handler :as level-handler]
            [cn.li.ac.ability.server.handlers.portable-dev-handler :as portable-dev-handler]
            [cn.li.ac.ability.server.handlers.common :as handler-common]
            [cn.li.ac.ability.server.handlers.preset-handler :as preset-handler]
            [cn.li.ac.ability.server.handlers.activation-handler :as activation-handler]
            [cn.li.ac.ability.service.platform-hooks :as platform-hooks]
            [cn.li.ac.ability.server.util.developer-validation :as dev-validate]
            [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.ac.wireless.gui.sync.handler :as net-helpers]
            [cn.li.mcmod.platform.world         :as world]
            [cn.li.mcmod.platform.be            :as platform-be]
            [cn.li.mcmod.util.log               :as log]))

(def ^:private fn-try-pull-developer-energy :ability/try-pull-developer-energy!)

  ;; ============================================================================
  ;; Helpers
  ;; ============================================================================

  (defn- get-state [uuid]
    (handler-common/get-state uuid))

  (defn- try-pull-developer-energy!
    [tile ^double amount]
    (if (platform-hooks/platform-fn-registered? fn-try-pull-developer-energy)
      (boolean ((platform-hooks/get-platform-fn fn-try-pull-developer-energy) tile amount))
      false))

  ;; ============================================================================
  ;; Skill learning
  ;; ============================================================================

  (defn- handle-learn-skill-request
    [payload player]
    (let [{:keys [skill-id pos-x pos-y pos-z]} payload
          uuid (uuid/player-uuid player)
          session-id (handler-common/current-server-session-id)
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
          server-world? (and world (not (world/client-side? world)))
          station
          (when all-coords?
            (cond (not server-world?) {:ok? false :reason :not-server}
                  (not tile) {:ok? false :reason :no-tile}
                  (not (dev-validate/developer-controller-tile? tile)) {:ok? false :reason :wrong-block}
                  (not (dev-validate/dist-sq-ok-for-station? player tile)) {:ok? false :reason :distance}
                  (not session-ok?) {:ok? false :reason :session}
                  (not (:structure-valid st)) {:ok? false :reason :structure}
                  :else {:ok? true :tile tile :developer-type (dev-validate/developer-type-for-tile tile)}))
                  skill-spec (skill/get-skill skill-id)
          do-learn! #(command-rt/run-command-in-session! session-id uuid {:command :learn-skill
                                                                          :skill-id skill-id
                                                                          :check-conditions? false})]
      (when-not (adata/is-learned? ad skill-id)
        (cond
          (and all-coords? (not (:ok? station)))
          (log/debug "learn-skill rejected (station)" uuid skill-id (:reason station))

          all-coords?
          (let [dev-t (:developer-type station)
                {:keys [pass? failures]} (if skill-spec
                                           (learning-rules/check-all-conditions skill-spec ad player-level dev-t)
                                           {:pass? false
                                            :failures [{:type :unknown-skill :skill-id skill-id}]})]
            (if pass?
              (let [cost (double (progression/learning-cost (long (:level skill-spec))))]
                (if (try-pull-developer-energy! (:tile station) cost)
                  (do-learn!)
                  (log/debug "learn-skill rejected (IF)" uuid skill-id cost)))
              (log/debug "learn-skill rejected" uuid skill-id failures)))

          :else
          (let [{:keys [pass? failures]} (if skill-spec
                                           (learning-rules/check-all-conditions skill-spec ad player-level :normal)
                                           {:pass? false
                                            :failures [{:type :unknown-skill :skill-id skill-id}]})]
            (if pass?
              (do-learn!)
              (log/debug "learn-skill rejected" uuid skill-id failures)))))))

  ;; ============================================================================
  ;; Registration
  ;; ============================================================================

  ;; Ability handlers do not operate on open GUI containers; they carry
  ;; self-contained payloads (activated, skill-id, category-id, etc.) and do
  ;; not need sync-routing validation.
(def ^:private ability-handler-contract
    {:owner-spec :server :payload-routing :none})

(defn- handle-combat-intent-request
  [payload player]
  (let [owner (uuid/player-uuid player)
        movement-keys #{:forward :back :left :right}
        movement-transitions #{:press :tick :release}
        raw-key (:movement-key payload)
        raw-transition (:movement-transition payload)
        movement? (= :movement (:op payload))
        valid-movement? (and movement?
                             (contains? movement-keys raw-key)
                             (contains? movement-transitions raw-transition))
        event (when valid-movement?
                (keyword "movement"
                         (str (name raw-key) "-" (name raw-transition))))
        ;; The client submits only a neutral movement fact.  The server owns
        ;; the event vocabulary and creative-mode truth.
        intent (cond-> (select-keys payload [:schema-version :intent-id :slot :client-tick])
                 (not movement?) (assoc :op (:op payload))
                 valid-movement? (assoc :op :event :action :event :event event)
                 true (assoc :creative? (boolean (entity/player-creative? player))))
        result (if (and movement? (not valid-movement?))
                 {:status :rejected :feedback [{:type :invalid-movement}]}
                 (combat-runtime/dispatch-intent! owner intent))
        result (if (= :accepted (:status result))
                 ;; publish-combat-result! both routes :vfx-signals to their
                 ;; audience (self/nearby-broadcast) through the push
                 ;; channel and strips them from the value returned here --
                 ;; the RPC reply itself only ever carries status/feedback,
                 ;; the same single-execution-path contract every other
                 ;; result-shaped payload in this module already follows.
                 (vfx-publish/publish-combat-result!
                  (:vfx (combat-catalog/catalog))
                  (combat-runtime/finalize-result! owner result))
                 result)]
    (when (= :rejected (:status result))
      (log/debug "Combat intent rejected" {:owner owner :feedback (:feedback result)}))
    result))

(defn register-handlers! []
  (net-srv/register-handler catalog/MSG-REQ-LEARN-NODE     handle-learn-skill-request    ability-handler-contract)
  (net-srv/register-handler catalog/MSG-REQ-LEVEL-UP       level-handler/handle-level-up-request ability-handler-contract)
  (net-srv/register-handler catalog/MSG-REQ-PORTABLE-DEV-START portable-dev-handler/handle-portable-dev-start-request ability-handler-contract)
  (net-srv/register-handler catalog/MSG-REQ-SET-PRESET     preset-handler/handle-set-preset-request ability-handler-contract)
  (net-srv/register-handler catalog/MSG-REQ-SWITCH-PRESET  preset-handler/handle-switch-preset-request ability-handler-contract)
  (net-srv/register-handler catalog/MSG-REQ-SET-ACTIVATED  activation-handler/handle-set-activated-request ability-handler-contract)
  (net-srv/register-handler catalog/MSG-COMBAT-INTENT
                            handle-combat-intent-request
                            ability-handler-contract)
  (log/info "Ability network handlers registered"))

(ns cn.li.ac.ability.adapters.server-hooks
  "Server/runtime hook composition for AC ability platform bridge."
  (:require [cn.li.ac.ability.config :as ability-config]
            [cn.li.ac.ability.item-actions :as item-actions]
            [cn.li.ac.ability.model.resource :as rdata]
            [cn.li.ac.ability.registry.event :as evt]
            [cn.li.ac.ability.registry.skill-query :as skill-query]
            [cn.li.ac.ability.server.damage.entity :as entity-damage-runtime]
            [cn.li.ac.ability.server.damage.handler :as damage-handler]
            [cn.li.ac.ability.server.damage.runtime :as damage-runtime]
            [cn.li.ac.ability.server.service.context-mgr :as ctx-mgr]
            [cn.li.ac.ability.server.service.delayed-projectiles :as delayed-projectiles]
            [cn.li.ac.ability.server.service.resource :as svc-res]
            [cn.li.ac.ability.service.dispatcher :as ctx]
            [cn.li.ac.ability.service.player-state :as ps]
            [cn.li.ac.ability.state.store :as ability-store]
            [cn.li.ac.util.init-guard :refer [with-init-guard]]
            [cn.li.mcmod.util.log :as log]))

(defonce ^:private lifecycle-subscriptions-registered? (atom false))

(defn install-store!
  []
  (ability-store/install-store!))

(defn register-lifecycle-subscriptions!
  []
  (with-init-guard lifecycle-subscriptions-registered?
    (evt/subscribe-ability-event!
     evt/EVT-LEVEL-CHANGE
     (fn [{:keys [uuid new-level]}]
       (when (and uuid new-level)
         (ps/update-resource-data! uuid
                                   (fn [rd]
                                     (svc-res/recalc-max-for-level rd new-level uuid))))))
    (evt/subscribe-ability-event!
     evt/EVT-SKILL-LEARN
     (fn [{:keys [uuid]}]
       (when uuid
         (when-let [state (ps/get-player-state uuid)]
           (let [level (get-in state [:ability-data :level] 1)]
             (ps/update-resource-data! uuid
                                       (fn [rd]
                                         (svc-res/recalc-max-for-level rd level uuid))))))))
    (evt/subscribe-ability-event!
     evt/EVT-CATEGORY-CHANGE
     (fn [{:keys [uuid]}]
       (when uuid
         (ps/update-resource-data! uuid rdata/set-activated false)
         (when-let [state (ps/get-player-state uuid)]
           (let [level (get-in state [:ability-data :level] 1)]
             (ps/update-resource-data! uuid
                                       (fn [rd]
                                         (svc-res/recalc-max-for-level rd level uuid))))))))
    (log/info "Ability lifecycle event subscriptions registered")))

(defn runtime-server-hooks
  []
  {:on-player-login!
   (fn [player-uuid]
     (ps/get-or-create-player-state! player-uuid))

   :on-player-logout!
   (fn [player-uuid]
     (ctx-mgr/abort-player-contexts! player-uuid)
     (ps/remove-player-state! player-uuid))

   :on-player-clone!
   (fn [_old-player-uuid _new-player-uuid]
     nil)

   :on-player-death!
   (fn [player-uuid]
     (ctx-mgr/abort-player-contexts! player-uuid))

   :on-player-dimension-change!
   (fn [player-uuid _from-dim _to-dim]
     (ctx-mgr/abort-player-contexts! player-uuid))

   :get-skills-for-category
   (fn [cat-id]
     (vec (skill-query/get-skills-for-category cat-id)))

   :on-player-tick!
   (fn [player-uuid]
     (ps/get-or-create-player-state! player-uuid)
     (ps/server-tick-player! player-uuid nil)
     (delayed-projectiles/tick-player! player-uuid)
     (ctx-mgr/tick-context-manager!))

   :list-player-uuids
   (fn []
     (ps/list-player-uuids))

   :build-sync-payload
   (fn [player-uuid]
     (when-let [state (ps/get-player-state player-uuid)]
       {:uuid player-uuid
        :ability-data (:ability-data state)
        :resource-data (:resource-data state)
        :cooldown-data (:cooldown-data state)
        :preset-data (:preset-data state)
        :develop-data (:develop-data state)
        :terminal-data (:terminal-data state)}))

   :mark-player-clean!
   (fn [player-uuid]
     (ps/mark-clean! player-uuid))

   :get-player-state
   (fn [player-uuid]
     (ps/get-player-state player-uuid))

   :set-player-state!
   (fn [player-uuid state]
     (ps/set-player-state! player-uuid state))

   :get-or-create-player-state!
   (fn [player-uuid]
     (ps/get-or-create-player-state! player-uuid))

   :fresh-player-state
   (fn []
     (ps/fresh-state))

   :runtime-activated?
   (fn [player-uuid]
     (boolean (some-> (ps/get-player-state player-uuid)
                      :resource-data
                      rdata/is-activated?)))

   :register-network-handlers!
   (fn []
     (if-let [f (requiring-resolve 'cn.li.ac.ability.server.network/register-handlers!)]
       (f)
       (log/warn "Ability network register-handlers! not available")))

   :subscribe-achievement-trigger!
   (fn [handler]
     (evt/subscribe-ability-event! evt/EVT-ACHIEVEMENT-TRIGGER handler))

   :register-context-route-fns!
   (fn [fns-map]
     (ctx/register-route-fns! fns-map))

   :register-context-send-fns!
   (fn [fns-map]
     (ctx-mgr/register-send-fns! fns-map))

   :get-context-player-uuid
   (fn [ctx-id]
     (when-let [ctx-map (ctx/get-context ctx-id)]
       (:player-uuid ctx-map)))

   :register-damage-handler!
   (fn [handler-id handler-fn priority]
     (damage-runtime/register-damage-handler! handler-id handler-fn priority))

   :unregister-damage-handler!
   (fn [handler-id]
     (damage-runtime/unregister-damage-handler! handler-id))

   :get-active-damage-handlers
   (fn []
     (damage-runtime/get-active-handlers))

   :process-damage-interception
   (fn [player-id attacker-id damage damage-source]
     (damage-runtime/process-damage! player-id attacker-id damage damage-source))

   :should-cancel-attack-interception?
   (fn [player-id attacker-id damage damage-source]
     (damage-handler/should-cancel-attack? player-id attacker-id damage damage-source))

   :run-attack-precheck-side-effects!
   (fn [player-id attacker-id damage damage-source]
     (damage-handler/run-attack-precheck-side-effects! player-id attacker-id damage damage-source))

   :resolve-item-use-action
   (fn [item-id]
     (item-actions/resolve-item-action item-id))

   :on-runtime-item-action!
   (fn [action player-uuid payload]
     (item-actions/on-item-action! action player-uuid payload))

   :build-item-use-plan
   (fn [_player-uuid item-id _activated? _side]
     (when-let [action (item-actions/resolve-item-action item-id)]
       (let [entity-spawn (item-actions/get-item-entity-spawn item-id)
             domain-payload (if (= action :railgun-coin-throw)
                              {:timestamp-ms (System/currentTimeMillis)}
                              {})
             server-actions (cond-> [{:kind :consume-item :count 1 :unless-instabuild? true}
                                     {:kind :domain-action :action action :payload domain-payload}]
                              entity-spawn (conj {:kind :spawn-scripted-effect
                                                  :entity-id (:entity-id entity-spawn)
                                                  :speed (double (or (:speed entity-spawn) 0.0))}))]
         {:server-actions server-actions
          :client-actions [{:kind :notify-local-effect
                            :event-key :ac/charge-coin-throw}]
          :consume? true})))

   :compute-aoe-damage
   (fn [origin-pos target-pos radius damage falloff?]
     (entity-damage-runtime/compute-aoe-damage origin-pos target-pos radius damage falloff?))

   :select-reflection-target
   (fn [current-entity-uuid current-pos candidates max-radius]
     (entity-damage-runtime/select-next-reflection-target current-entity-uuid current-pos candidates max-radius))

   :reflection-search-radius
   (fn []
     (ability-config/reflection-search-radius))

   :compute-reflected-damage
   (fn [current-damage]
     (entity-damage-runtime/compute-reflected-damage current-damage))})

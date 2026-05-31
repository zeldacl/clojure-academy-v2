(ns cn.li.ac.ability.adapters.server-hooks
  "Server/runtime hook composition for AC ability platform bridge."
  (:require 
            [cn.li.ac.ability.service.state-tick :as ps-tick]
[cn.li.ac.ability.service.state-accessors :as ps-accessors]
[cn.li.ac.ability.service.runtime-store :as store]
[cn.li.ac.ability.config :as ability-config]
            [cn.li.ac.ability.item-actions :as item-actions]
            [cn.li.ac.ability.model.cooldown :as cdata]
            [cn.li.ac.ability.model.resource :as rdata]
            [cn.li.ac.ability.registry.event :as evt]
            [cn.li.ac.ability.registry.skill-query :as skill-query]
            [cn.li.ac.ability.server.network :as network]
            [cn.li.ac.ability.server.damage.entity :as entity-damage-runtime]
            [cn.li.ac.ability.server.damage.handler :as damage-handler]
            [cn.li.ac.ability.server.damage.runtime :as damage-runtime]
            [cn.li.ac.ability.service.context-manager :as ctx-mgr]
            [cn.li.ac.ability.service.delayed-projectiles :as delayed-projectiles]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.content.ability.meltdowner.damage-helper :as md-damage]
            [cn.li.ac.ability.service.platform-hooks :as platform-hooks]            [cn.li.ac.block.developer.logic :as developer-logic]
            [cn.li.ac.content.ability.server-runtime-lifecycle :as server-runtime-lifecycle]
            [cn.li.ac.wireless.data.world-registry :as world-registry]
            [cn.li.ac.util.init-guard :refer [with-init-guard]]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.util.log :as log]))

(def ^:private fn-reset-server-runtimes :ability/reset-server-runtimes!)
(def ^:private fn-register-network-handlers :ability/register-network-handlers!)
(def ^:private fn-try-pull-developer-energy :ability/try-pull-developer-energy!)

(defn default-lifecycle-subscriptions-runtime-state
  []
  false)

(defn create-lifecycle-subscriptions-runtime
  ([]
   (create-lifecycle-subscriptions-runtime {}))
  ([{:keys [state*]
     :or {state* (atom (default-lifecycle-subscriptions-runtime-state))}}]
   {::runtime ::lifecycle-subscriptions-runtime
    :state* state*}))

(defonce ^:private installed-lifecycle-subscriptions-runtime
  (create-lifecycle-subscriptions-runtime))

(def ^:dynamic *lifecycle-subscriptions-runtime*
  installed-lifecycle-subscriptions-runtime)

(defn- lifecycle-subscriptions-runtime?
  [runtime]
  (and (map? runtime)
       (= ::lifecycle-subscriptions-runtime (::runtime runtime))
       (some? (:state* runtime))))

(defn call-with-lifecycle-subscriptions-runtime
  [runtime f]
  (when-not (lifecycle-subscriptions-runtime? runtime)
    (throw (ex-info "Expected lifecycle subscriptions runtime"
                    {:runtime runtime})))
  (binding [*lifecycle-subscriptions-runtime* runtime]
    (f)))

(defmacro with-lifecycle-subscriptions-runtime
  [runtime & body]
  `(call-with-lifecycle-subscriptions-runtime ~runtime (fn [] ~@body)))

(defn- current-lifecycle-subscriptions-runtime
  []
  *lifecycle-subscriptions-runtime*)

(defn- lifecycle-subscriptions-registered-atom
  []
  (:state* (current-lifecycle-subscriptions-runtime)))

(defn- unique-context-by-id
  [ctx-id]
  (let [matches (->> (ctx/snapshot-context-registry)
                     vals
                     (filter #(= ctx-id (:id %)))
                     vec)]
    (when (= 1 (count matches))
      (first matches))))

(defn- runtime-get-player-state
  [player-uuid]
  (store/get-player-state* (runtime-hooks/require-player-state-session-id "Server hooks runtime state access") player-uuid))

(defn- runtime-get-or-create-player-state!
  [player-uuid]
  (store/get-or-create-player-state! (runtime-hooks/require-player-state-session-id "Server hooks runtime state access") player-uuid))

(defn- runtime-set-player-state!
  [player-uuid state]
  (store/set-player-state!* (runtime-hooks/require-player-state-session-id "Server hooks runtime state access") player-uuid state))

(defn- runtime-mark-clean!
  [player-uuid]
  (store/clear-dirty! (store/get-store)
                      (runtime-hooks/require-player-state-session-id "Server hooks runtime state access")
                      player-uuid))

(defn- runtime-list-player-uuids
  []
  (store/list-players (store/get-store)
                      (runtime-hooks/require-player-state-session-id "Server hooks runtime state access")))

(defn- recalc-max-for-level-with-calc
  [res-data level uuid]
  (let [base (rdata/recalc-max-values res-data level)
        calc-extra {:uuid uuid}
        max-cp (evt/fire-calc-event! evt/CALC-MAX-CP (:max-cp base) calc-extra)
        max-ol (evt/fire-calc-event! evt/CALC-MAX-OVERLOAD (:max-overload base) calc-extra)]
    (assoc base
           :max-cp max-cp
           :max-overload max-ol
           :cur-cp (min (:cur-cp base) max-cp)
           :cur-overload (min (:cur-overload base) max-ol))))

(defn lifecycle-subscriptions-registered-snapshot
  []
  @(lifecycle-subscriptions-registered-atom))

(defn reset-lifecycle-subscriptions-registered-for-test!
  ([]
   (reset-lifecycle-subscriptions-registered-for-test! false))
  ([registered?]
  (reset! (lifecycle-subscriptions-registered-atom) (boolean registered?))
   nil))

(defn register-platform-functions!
  "Register platform-facing callbacks used by reducer/effects/network shells."
  []
  (platform-hooks/register-platform-fn! fn-reset-server-runtimes
                                       server-runtime-lifecycle/reset-ability-server-runtimes!)
  (platform-hooks/register-platform-fn! fn-register-network-handlers
                                       network/register-handlers!)
  (platform-hooks/register-platform-fn! fn-try-pull-developer-energy
                                       developer-logic/try-pull-energy!)
  nil)

(defn register-lifecycle-subscriptions!
  []
  (with-init-guard (lifecycle-subscriptions-registered-atom)
    (evt/subscribe-ability-event!
     evt/EVT-LEVEL-CHANGE
     (fn [{:keys [uuid new-level]}]
       (when (and uuid new-level)
         (ps-accessors/update-resource-data! uuid
                                   (fn [rd]
                                     (recalc-max-for-level-with-calc (rdata/reset-add-max rd)
                                                                      new-level
                                                                      uuid))))))
    (evt/subscribe-ability-event!
     evt/EVT-SKILL-LEARN
     (fn [{:keys [uuid]}]
       (when uuid
         (when-let [state (runtime-get-player-state uuid)]
           (let [level (get-in state [:ability-data :level] 1)]
             (ps-accessors/update-resource-data! uuid
                                       (fn [rd]
                                         (recalc-max-for-level-with-calc rd level uuid))))))))
    (evt/subscribe-ability-event!
     evt/EVT-CATEGORY-CHANGE
     (fn [{:keys [uuid]}]
       (when uuid
         (ctx-mgr/abort-player-contexts! uuid)
         (ps-accessors/update-cooldown-data! uuid (constantly (cdata/new-cooldown-data)))
         (ps-accessors/update-resource-data! uuid rdata/set-activated false)
         (when-let [state (runtime-get-player-state uuid)]
           (let [level (get-in state [:ability-data :level] 1)]
             (ps-accessors/update-resource-data! uuid
                                       (fn [rd]
                                         (recalc-max-for-level-with-calc rd level uuid))))))))
    (evt/subscribe-ability-event!
     evt/EVT-OVERLOAD
     (fn [{:keys [uuid]}]
       (when uuid
         (ctx-mgr/abort-player-contexts! uuid))))
    (log/info "Ability lifecycle event subscriptions registered")))

(defn runtime-server-hooks
  []
  {:on-player-login!
   (fn [player-uuid]
     (runtime-get-or-create-player-state! player-uuid))

   :on-player-logout!
   (fn [player-uuid]
     (ctx-mgr/abort-player-contexts! player-uuid)
     (delayed-projectiles/clear-player-tasks! player-uuid)
     (md-damage/clear-target-mark! player-uuid)
     (md-damage/clear-source-marks! player-uuid)
     (store/remove-player-state!* (runtime-hooks/require-player-state-session-id "Server hooks runtime state access")
                                  player-uuid))

   :on-server-stop!
   (fn [session-id]
     (ctx/clear-session-contexts! session-id)
     (store/remove-session! (store/get-store) session-id)
     (world-registry/clear-session-world-data! session-id)
     (when (platform-hooks/platform-fn-registered? fn-reset-server-runtimes)
       ((platform-hooks/get-platform-fn fn-reset-server-runtimes)))
     (delayed-projectiles/clear-all-tasks!)
     (md-damage/clear-all-marks!))

   :on-player-clone!
   (fn [_old-player-uuid _new-player-uuid]
     nil)

   :on-player-death!
   (fn [player-uuid]
     (delayed-projectiles/clear-player-tasks! player-uuid)
     (md-damage/clear-target-mark! player-uuid)
     (md-damage/clear-source-marks! player-uuid)
     (ctx-mgr/abort-player-contexts! player-uuid))

   :on-player-dimension-change!
   (fn [player-uuid _from-dim _to-dim]
     (delayed-projectiles/clear-player-tasks! player-uuid)
     (md-damage/clear-target-mark! player-uuid)
     (md-damage/clear-source-marks! player-uuid)
     (ctx-mgr/abort-player-contexts! player-uuid))

   :get-skills-for-category
   (fn [cat-id]
     (vec (skill-query/get-skills-for-category cat-id)))

   :on-player-tick!
   (fn [player-uuid]
     (runtime-get-or-create-player-state! player-uuid)
     (md-damage/tick-marks!)
     (ps-tick/server-tick-player-in-session! (runtime-hooks/require-player-state-session-id "Server hooks runtime state access")
                                             player-uuid
                                             nil)
     (ctx-mgr/tick-player-contexts! player-uuid)
     (delayed-projectiles/tick-player! player-uuid)
     (ctx-mgr/tick-context-manager!))

   :list-player-uuids
   (fn []
     (runtime-list-player-uuids))

   :build-sync-payload
   (fn [player-uuid]
     (when-let [state (runtime-get-player-state player-uuid)]
       {:uuid player-uuid
        :ability-data (:ability-data state)
        :resource-data (:resource-data state)
        :cooldown-data (:cooldown-data state)
        :preset-data (:preset-data state)
        :develop-data (:develop-data state)
        :terminal-data (:terminal-data state)}))

   :mark-player-clean!
   (fn [player-uuid]
     (runtime-mark-clean! player-uuid))

   :get-player-state
   (fn [player-uuid]
     (runtime-get-player-state player-uuid))

   :set-player-state!
   (fn [player-uuid state]
     (runtime-set-player-state! player-uuid state))

   :get-or-create-player-state!
   (fn [player-uuid]
     (runtime-get-or-create-player-state! player-uuid))

   :fresh-player-state
   (fn []
     (store/fresh-player-state))

   :runtime-activated?
   (fn [player-uuid]
     (boolean (some-> (runtime-get-player-state player-uuid)
                      :resource-data
                      rdata/is-activated?)))

   :register-network-handlers!
   (fn []
     (if (platform-hooks/platform-fn-registered? fn-register-network-handlers)
       ((platform-hooks/get-platform-fn fn-register-network-handlers))
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
     (when-let [ctx-map (or (when ctx/*context-owner*
                               (ctx/get-context ctx-id))
                            (unique-context-by-id ctx-id))]
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




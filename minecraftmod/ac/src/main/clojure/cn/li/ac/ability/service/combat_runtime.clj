(ns cn.li.ac.ability.service.combat-runtime
  "AC composition root for the neutral combat engine.

   Combat Core itself never knows about AC, Minecraft or VFX."
  (:require [cn.li.combat.registry :as registry]
            [cn.li.combat.compiler :as compiler]
            [cn.li.combat.runtime :as combat]
            [cn.li.ac.ability.service.runtime-store :as runtime-store]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.ac.ability.model.preset :as preset-data]
            [cn.li.ac.ability.registry.skill-query :as skill-query]
            [cn.li.ac.ability.service.command-runtime :as command-runtime]
            [cn.li.ac.ability.model.ability :as ability-model]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.runtime.combat-contract :as contract]))

(defonce ^:private engine* (atom nil))
(defonce ^:private catalog* (atom nil))
(defonce ^:private world-effect-handler* (atom nil))
(declare owner-state resolve-slot)

(defn- academy-damage-pipeline
  "Pure AC-owned damage transforms contributed by passive Combat abilities.

   The transform only reads the immutable owner snapshot supplied to Combat
   Core.  It never reaches the player store or installs a platform damage
   listener, so passive skills remain part of the deterministic pipeline." 
  []
  [{:priority 100
    :provider-id :academy/base
    :ability-id :rad-intensify
    :node-id :damage-amplifier
    :run (fn [request context]
           (let [exp (double (ability-model/get-skill-exp
                              (get-in context [:state :ability-data])
                              :rad-intensify))
                 multiplier (+ 1.4 (* 0.4 exp))]
             (update request :base #(* (double %) multiplier))))}])

(defn initialize!
  ([] (initialize! {}))
  ([{:keys [owner-state-fn query-port now-tick ability-resolver damage-pipeline]}]
   (or @engine*
       (let [catalog (compiler/compile-all!)
             default-query-port
             {:raycast (fn [context node]
                         (if-let [host-query (contract/host-port :query)]
                           (host-query :raycast context node)
                           (when (raycast/available?)
                             (let [owner (:owner context)
                                   hit (raycast/raycast-from-player
                                        owner
                                        (double (or (:distance node) 12.0))
                                        true)
                                   position (raycast/player-position owner)]
                               (cond-> hit
                                 (and (map? position) (:world-id position))
                                 (assoc :world-id (:world-id position)))))))
              :entities (fn [context node]
                          (when-let [host-query (contract/host-port :query)]
                            (host-query :entities context node)))
              :charge-target (fn [context node]
                               (when-let [host-query (contract/host-port :query)]
                                 (host-query :charge-target context node)))
              :block-scan (fn [context node]
                            (when-let [host-query (contract/host-port :query)]
                              (host-query :block-scan context node)))}]
         (when-not (registry/frozen?) (registry/freeze!))
         (reset! catalog* catalog)
         (reset! engine* (combat/create-engine
                           {:catalog catalog
                            :initial-owner-state (or owner-state-fn owner-state)
                            :query-port (merge default-query-port (or query-port {}))
                            :now-tick now-tick
                            :ability-resolver (or ability-resolver resolve-slot)
                            :damage-pipeline (or damage-pipeline
                                                 (academy-damage-pipeline))}))
         (when-not @world-effect-handler*
           (reset! world-effect-handler*
                   (fn [owner effect]
                     (if-let [handler (contract/host-port :world-effect)]
                       (handler owner effect)
                       (case (:type effect)
                         :damage
                         (let [{:keys [request]} effect
                               {:keys [world-id target base type source]} request]
                           {:status (if (and world-id target
                                              (entity-damage/available?)
                                              (entity-damage/apply-direct-damage!
                                               world-id target base type
                                               {:attacker-uuid source}))
                                        :applied :failed)
                            :effect effect})
                         :damage-aoe
                         (let [{:keys [world-id origin radius amount damage-type]} effect
                               {:keys [x y z]} origin]
                           {:status (if (and world-id origin
                                              (entity-damage/available?)
                                              (entity-damage/apply-aoe-damage!
                                               world-id x y z (double radius)
                                               (double amount) damage-type false))
                                        :applied :failed)
                            :effect effect})
                         {:status :unhandled
                          :reason :missing-world-effect-host-port
                          :effect effect}))))
         @engine*)))))

(defn engine [] (or @engine* (initialize!)))
(defn catalog [] @catalog*)
(defn content-hash [] (:content-hash @catalog*))
(defn register-provider! [provider]
  (registry/register-provider! provider))

(defn- server-session-id []
  (runtime-hooks/player-state-server-session-id))

(defn owner-state
  "Project AC's authoritative player state into Combat Core's neutral view.
   Combat Core never sees the original AC store shape." 
  [owner]
  (let [state (runtime-store/get-player-state (server-session-id) (str owner))
        resource-data (:resource-data state)
        cooldown-data (:cooldown-data state)]
    {:resources {:cp (double (or (:cur-cp resource-data) 0.0))}
     :cooldowns (into {}
                     (map (fn [[[ctrl-id _sub-id] value]]
                            [ctrl-id (long (or (:ticks value) 0))])
                          cooldown-data))
     :ability-data (:ability-data state)
     :preset-data (:preset-data state)}))

(defn resolve-slot
  "Resolve a client slot only against the server-authoritative preset." 
  [owner intent]
  (when-let [state (runtime-store/get-player-state (server-session-id) (str owner))]
    (let [slots (preset-data/get-active-slots (:preset-data state))
          slot (nth slots (long (:slot intent)) nil)]
      (when (and (vector? slot) (= 2 (count slot)))
        (skill-query/get-skill-by-controllable (first slot) (second slot))))))
(defn- commit-state-patch! [owner patches]
  (let [session-id (server-session-id)]
    (doseq [[kind key amount] patches]
      (case kind
        :resource
        (when (= key :cp)
          (command-runtime/run-command-in-session!
            session-id owner {:command :consume-resource :cp (- (double amount))}))
        :ability-exp
        (command-runtime/run-command-in-session!
          session-id owner {:command :add-skill-exp
                            :skill-id key
                            :amount (double amount)
                            :source :combat-core})
        :cooldown
        (let [ticks (max 0 (long (- amount (long ((:now-tick (engine)))))))]
          (command-runtime/run-command-in-session!
            session-id owner {:command :set-cooldown
                              :ctrl-id key
                              :sub-id :main
                              :ticks ticks}))))))

(defn dispatch-intent! [owner intent]
  (let [result (combat/dispatch-intent! (engine) owner intent)]
    (when (= :accepted (:status result))
      (commit-state-patch! owner (:state-patch result)))
    result))
(defn dispatch-domain-event! [event] (combat/dispatch-domain-event! (engine) event))
(defn install-world-effect-handler!
  "Install AC's ordered WorldEffect interpreter.

   The handler is injected by the platform composition root and receives
   `[owner effect]`. Combat Core never calls it directly; this keeps world
   mutation outside the neutral engine while making effect execution explicit
   and observable." 
  [handler]
  (when-not (ifn? handler)
    (throw (ex-info "world-effect handler must be callable" {:value handler})))
  (reset! world-effect-handler* handler)
  handler)

(defn execute-world-effects!
  "Execute WorldEffects in result order and return EffectResults.

   Missing host wiring is reported as a structured result instead of being
   silently discarded. Resource commits have already happened by this point;
   callers must model compensation explicitly." 
  [owner result]
  (let [handler @world-effect-handler*
        effect-results
        (mapv (fn [effect]
                (if-not handler
                  (contract/effect-result {:status :unhandled
                                           :reason :missing-world-effect-handler
                                           :effect effect})
                  (try
                    (contract/effect-result (handler owner effect))
                    (catch Throwable throwable
                      (contract/effect-result
                       {:status :failed
                        :reason :world-effect-exception
                        :effect effect
                        :message (ex-message throwable)})))))
              (:world-effects result))]
    (assoc result :effect-results effect-results)))
(defn tick! [tick] (combat/tick! (engine) tick))
(defn abort-owner! [owner] (combat/abort-owner! (engine) owner))
(defn snapshot-owner [owner] (combat/snapshot-owner (engine) owner))

(defn reset-for-test! []
  (reset! engine* nil)
  (reset! catalog* nil)
  (reset! world-effect-handler* nil)
  nil)

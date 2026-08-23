(ns cn.li.combat.v3-runtime-test
  "Proves cn.li.combat.v3-runtime/execute! against the REAL
   cn.li.mcmod.runtime.capabilities registry -- not a hand-built fake
   :dispatch-action!/:dispatch-query! the way
   cn.li.combat.end-to-end-ability-test exercises the dispatch/source-node
   chain itself. This is the other half of the same proof: the bridge
   from that chain to the actual capability registry real ability content
   will run against, using the same save/restore-a-real-capability
   pattern cn.li.combat.interception-test already established."
  (:require [clojure.test :refer [deftest is]]
            [clojure.set :as set]
            [cn.li.node.descriptor :as node]
            [cn.li.node.flow :as node-flow]
            [cn.li.mcmod.runtime.capabilities :as capabilities]
            [cn.li.combat.source-nodes :as source-nodes]
            [cn.li.combat.host-primitives :as host-primitives]
            [cn.li.combat.policy-primitives :as policy-primitives]
            [cn.li.combat.structural-primitives :as structural]
            [cn.li.combat.v3-runtime :as v3-runtime]))

(defn- with-fake-entity-damage-handler [f]
  (let [previous (get (:actions (capabilities/snapshot)) :entity/damage)
        seen (atom [])]
    (try
      (capabilities/register-action!
       :entity/damage
       (fn [request] (swap! seen conj request) {:status :applied}))
      (f seen)
      (finally
        (when previous
          (capabilities/register-action! :entity/damage previous))))))

(defn- fresh-node-registry! []
  (node/reset-for-test!)
  (node-flow/install!)
  (source-nodes/install!)
  (host-primitives/install!)
  (policy-primitives/install!)
  (structural/install!))

(def ^:private program
  {:component :flow/sequence
   :steps
   [{:component :ability/caster :bind {:id :owner-id}}
    {:component :ability/tunable :name :damage :bind {:value :dmg}}
    {:component :combat/damage :target {:ref [:local :owner-id]} :amount {:ref [:local :dmg]}}]})

(deftest execute-runs-a-real-program-through-the-real-capability-registry-test
  (fresh-node-registry!)
  (with-fake-entity-damage-handler
    (fn [seen]
      (let [result (v3-runtime/execute!
                    program
                    {:owner "player-1" :world-id "overworld" :ability-id :test-ability
                     :activation-seed 3
                     :caster-facade {:caster/id "player-1"}
                     :tunables {:damage 6.0}
                     :capability-state (capabilities/snapshot)})]
        (is (not (:finished? result)))
        (is (= "player-1" (get-in result [:locals :owner-id])))
        (is (= 6.0 (get-in result [:locals :dmg])))
        (is (= 1 (count @seen)))
        (is (= {:target "player-1" :amount 6.0} (select-keys (first @seen) [:target :amount]))))))
  (node/reset-for-test!))

(deftest dispatch-fns-return-nil-for-an-unregistered-capability-test
  (let [{:keys [dispatch-action! dispatch-query!]} (v3-runtime/dispatch-fns {:actions {} :queries {}})]
    (is (nil? (dispatch-action! :entity/damage {})))
    (is (nil? (dispatch-query! :raycast {})))))

(deftest build-ctx-defaults-emit-fns-to-safe-no-ops-test
  (let [ctx (v3-runtime/build-ctx {:owner "p" :capability-state {:actions {} :queries {}}})]
    (is (nil? ((:emit-vfx! ctx) {:effect-id :x})))
    (is (nil? ((:emit-action! ctx) {:type :owner-patch})))
    (is (nil? ((:emit-event! ctx) {:type :whatever})))))

(ns cn.li.combat.end-to-end-ability-test
  "Proves the full v3 execution chain works together for the first time:
   :layer :source nodes (reading ctx's :env, cn.li.combat.source-runtime)
   feeding a realistic ability-shaped program -- read the caster facade,
   look up a tunable and a cost budget, spend the budget, branch on
   affordability, apply damage -- through combat's real dispatch
   (cn.li.combat.structural-primitives/dispatch) and node-core's shared
   :flow/sequence/:flow/branch. This is the piece source_nodes.clj's own
   docstring flagged as still missing ('a future version also knows about
   the :ability/* source nodes, once those are wired into execution
   rather than only registered as descriptors') -- this test is that
   future version, exercised end to end with a real (if fake-dispatched)
   :env, not a synthetic single-node fixture."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.node.descriptor :as node]
            [cn.li.node.flow :as node-flow]
            [cn.li.combat.source-nodes :as source-nodes]
            [cn.li.combat.host-primitives :as host-primitives]
            [cn.li.combat.policy-primitives :as policy-primitives]
            [cn.li.combat.structural-primitives :as structural]))

(use-fixtures :each
  (fn [f]
    (node/reset-for-test!)
    (node-flow/install!)
    (source-nodes/install!)
    (host-primitives/install!)
    (policy-primitives/install!)
    (structural/install!)
    (f)
    (node/reset-for-test!)))

(def ^:private program
  {:component :flow/sequence
   :steps
   [{:component :ability/caster :bind {:aim :aim :body :body :id :owner-id :world-id :wid}}
    {:component :ability/tunable :name :damage :bind {:value :dmg}}
    {:component :ability/budget :name :release :bind {:budget :budget}}
    {:component :cost/spend :budget {:ref [:local :budget]} :bind {:insufficient? :insufficient?}}
    {:component :flow/branch
     :when {:ref [:local :insufficient?]}
     :then {:component :flow/finish :outcome :insufficient-resource}
     :else {:component :combat/damage
            :target {:ref [:local :owner-id]}
            :amount {:ref [:local :dmg]}}}]})

(defn- run-program [env resources]
  (let [actions (atom [])
        patches (atom [])
        resources* (atom resources)
        ctx {:locals {} :seed 0 :dispatch structural/dispatch
             :env env
             :world-id "overworld" :owner "target-1" :ability-id :test-ability :activation-seed 7
             :resources* resources*
             :dispatch-action! (fn [capability request] (swap! actions conj [capability request]))
             :dispatch-query! (fn [_ _] nil)
             :emit-action! (fn [action] (swap! patches conj action))}]
    {:result (node-flow/execute! program ctx)
     :actions @actions
     :patches @patches
     :resources @resources*}))

(def ^:private env
  {:caster-facade {:caster/aim {:x 0.0 :y 0.0 :z 1.0}
                   :caster/body {:x 0.0 :y 64.0 :z 0.0}
                   :caster/id "player-1"
                   :world/id "overworld"}
   :tunables {:damage 9.5}
   :costs {:release {:resources {:cp 3.0}}}
   :progression {}
   :cooldown {}
   :invariants {}})

(deftest full-chain-spends-budget-and-applies-damage-when-affordable-test
  (let [{:keys [result actions patches resources]} (run-program env {:cp 5.0})]
    (is (not (:finished? result)))
    (is (= {:x 0.0 :y 0.0 :z 1.0} (get-in result [:locals :aim])))
    (is (= "player-1" (get-in result [:locals :owner-id])))
    (is (= 9.5 (get-in result [:locals :dmg])))
    (is (= [[:entity/damage {:target "player-1" :amount 9.5}]]
           (mapv (fn [[cap req]] [cap (select-keys req [:target :amount])]) actions)))
    (is (= 1 (count patches)))
    (is (= :owner-patch (:type (first patches))))
    (is (= 2.0 (:cp resources)) "5.0 - 3.0 spent")))

(deftest full-chain-finishes-insufficient-and-skips-damage-when-unaffordable-test
  (let [{:keys [result actions patches resources]} (run-program env {:cp 1.0})]
    (is (:finished? result))
    (is (= :insufficient-resource (:outcome result)))
    (is (empty? actions))
    (is (empty? patches))
    (is (= 1.0 (:cp resources)) "nothing spent on partial failure")))

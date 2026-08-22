(ns cn.li.combat.interception-test
  "Regression coverage for the damage-interception boundary now owned
   entirely by combat-core (previously split across AC's combat_runtime.clj
   -- see [[feedback-single-combat-execution-path]]): fact-gathering, the
   declarative reaction pipeline, and applying reaction-produced damage
   through the registered :entity/damage capability all happen in one call,
   never through a second raw platform port."
  (:require [clojure.test :refer [deftest is]]
            [cn.li.mcmod.runtime.capabilities :as capabilities]
            [cn.li.combat.interception :as interception]))

(def ^:private reflect-ability
  {:id :reflect-passive
   :activation :passive
   :reactions [{:on :combat/damage
                :priority 1
                :program {:component :damage/reflect
                          :multiplier 0.5
                          :cost-per-damage 0.1
                          :minimum 0.1
                          :max-depth 1
                          :exp-scale 0.0}}]})

(defn- fixed-state [_owner]
  {:ability-data {:learned-skills #{:reflect-passive}
                  :skill-exps {:reflect-passive 0.0}}
   :resources {:cp 10.0}})

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

(deftest reflected-damage-is-applied-through-the-registered-entity-damage-capability-test
  (with-fake-entity-damage-handler
    (fn [seen]
      (let [result (interception/intercept!
                    {:target-id "target" :attacker-id "attacker" :base 10.0
                     :damage-type :magic :damage-source {:attacker-front? true}
                     :reactions [reflect-ability]
                     :session-fn (constantly nil)
                     :state-fn fixed-state
                     :domain-state {}
                     :tunables-fn (fn [_ _ _] {})
                     :now-tick 1
                     :front-cone-degrees 180.0
                     :precheck? false})]
        (is (not (:cancelled? result)))
        (is (= 1 (count @seen))
            "the reflected hit must land through the registered :entity/damage capability, never a raw platform port")
        (is (= {:world-id nil :target "attacker" :amount 5.0
                :damage-type :magic :owner "target"}
               (first @seen)))
        (is (true? (:reaction-damage-applied? result))
            "the caller (AC) needs this fact to decide whether to cancel the native hit")))))

(deftest a-self-targeted-reflection-never-reaches-the-capability-test
  (with-fake-entity-damage-handler
    (fn [seen]
      ;; attacker == target: even if a reaction somehow tried to reflect
      ;; damage back onto its own source, the world-effect must fail closed
      ;; rather than land a self-hit through the capability.
      (let [result (interception/intercept!
                    {:target-id "target" :attacker-id "target" :base 10.0
                     :damage-type :magic :damage-source {:attacker-front? true}
                     :reactions [reflect-ability]
                     :session-fn (constantly nil)
                     :state-fn fixed-state
                     :domain-state {}
                     :tunables-fn (fn [_ _ _] {})
                     :now-tick 1
                     :front-cone-degrees 180.0
                     :precheck? false})]
        (is (empty? @seen))
        (is (false? (:reaction-damage-applied? result)))))))

(deftest attacker-front-uses-the-damage-sources-own-fact-when-present-test
  (is (true? (interception/attacker-front? "t" "a" {:attacker-front? true} 10.0)))
  (is (false? (interception/attacker-front? "t" "a" {:attacker-front? false} 10.0))))

(deftest attacker-front-defaults-true-when-there-is-no-attacker-test
  (is (true? (interception/attacker-front? "t" nil {} 10.0))))

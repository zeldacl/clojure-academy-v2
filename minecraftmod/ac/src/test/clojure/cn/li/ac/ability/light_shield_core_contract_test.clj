(ns cn.li.ac.ability.light-shield-core-contract-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.service.light-shield-state :as shield]
            [cn.li.ac.ability.service.combat-runtime :as combat]
            [cn.li.combat.damage :as damage]
            [cn.li.combat.registry :as registry]
            [cn.li.combat.compiler :as compiler]
            [cn.li.combat.runtime :as runtime]
            [cn.li.ac.ability.service.combat-content :as content]))

(deftest combat-core-owns-light-shield-state-machine
  (let [started (shield/start 7.0)
        ticked (shield/tick started)
        [absorbed remaining amount] (shield/absorb ticked 20.0 12.0 12)
        ended (combat/apply-combat-domain-event
               {:light-shields {"p" absorbed}}
               {:type :light-shield-end :owner "p"})]
    (is (= 7.0 (:overload-floor started)))
    (is (= 1 (:ticks ticked)))
    (is (= 12 (:last-absorb-tick absorbed)))
    (is (= 8.0 remaining))
    (is (= 12.0 amount))
    (is (nil? (get-in ended [:light-shields "p"])))))

(deftest combat-core-light-shield-absorption-is-front-cone-and-resource-gated
  (let [pipeline (damage/compile-pipeline
                  (#'cn.li.ac.ability.service.combat-runtime/academy-damage-pipeline))
        base {:source "attacker" :target "p" :base 20.0 :type :generic
              :components {:direct 20.0}}
        context {:target-state {:resources {:cp 100.0 :overload 100.0}
                                :ability-data {:skill-exps {:light-shield 0.0}}}
                 :domain-state {:light-shields {"p" (shield/start 0.0)}}
                 :tick 20}
        front (damage/apply-pipeline
               pipeline (assoc base :metadata {:attacker-front? true}) context)
        rear (damage/apply-pipeline
              pipeline (assoc base :metadata {:attacker-front? false}) context)]
    (is (< (:base front) 20.0))
    (is (= 20.0 (:base rear)))
    (is (= {:overload -5.0 :cp -50.0}
           (get-in front [:metadata :resource-cost])))))

(deftest combat-core-light-shield-session-covers-start-pulse-release
  (registry/reset-for-test!)
  (content/reset-for-test!)
  (content/register! registry/register-provider!)
  (let [engine (runtime/create-engine
                {:catalog (compiler/compile-all!)
                 :now-tick (constantly 0)
                 :initial-owner-state
                 (fn [_] {:resources {:cp 100.0 :overload 120.0}
                           :ability-data {:skill-exps {:light-shield 0.0}}})
                 :query-port
                 {:light-shield (fn [_ _]
                                  {:world-id "world"
                                   :entities []})}})
        started (runtime/dispatch-intent!
                 engine "owner" {:intent-id 1 :op :start :ability-id :light-shield})
        pulsed (first (runtime/tick! engine 1))
        released (runtime/dispatch-intent!
                  engine "owner" {:intent-id 2 :op :release :ability-id :light-shield})]
    (is (= :accepted (:status started)))
    (is (= :light-shield-start (get-in started [:events 0 :type])))
    (is (= :light-shield (get-in pulsed [:world-effects 0 :type])))
    (is (= :active (get-in pulsed [:vfx-signals 0 :event])))
    (is (= :light-shield-end (get-in released [:events 0 :type])))
    (is (= :end (get-in released [:vfx-signals 0 :event])))))

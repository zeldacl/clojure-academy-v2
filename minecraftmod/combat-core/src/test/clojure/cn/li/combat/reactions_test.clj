(ns cn.li.combat.reactions-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.combat.reactions :as reactions]))

(deftest critical-reaction-preserves-neutral-damage-shape-test
  (let [ability {:id :critical-passive
                 :activation :passive
                 :reactions [{:on :combat/damage
                              :priority 1
                              :program {:component :damage/critical
                                        :levels [{:level 0
                                                  :probability 1.0
                                                  :multiplier 1.3}]
                                        :damage-types [:magic]
                                        :exp-per-level 0.005}}]}
        request {:source "attacker"
                 :target "target"
                 :base 10.0
                 :type :magic
                 :components {:direct 10.0}
                 :tags #{:combat}
                 :metadata {:activation-seed 7
                            :world-id "world"}}
        result (reactions/apply!
                request
                {:reactions [ability]
                 :session-fn (constantly nil)
                 :state-fn (fn [_]
                             {:ability-data {:learned-skills #{:critical-passive}
                                             :skill-exps {:critical-passive 0.2}}})
                 :tunables-fn (fn [_ _ _] {})
                 :domain-state {}})]
    (is (= "attacker" (:source result)))
    (is (= "target" (:target result)))
    (is (= :magic (:type result)))
    (is (= 13.0 (:base result)))
    (is (= 13.0 (get-in result [:components :direct])))
    (is (= 0 (get-in result [:metadata :critical :level])))
    (is (= [[:ability-exp :critical-passive 0.005]]
           (:source-state-patch result)))))

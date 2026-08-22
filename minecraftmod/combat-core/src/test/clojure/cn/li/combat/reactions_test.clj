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
    (is (= [{:path [:ability-data :skill-exps :critical-passive]
             :mode :increment :value 0.005}]
           (:source-state-patch result)))))

(deftest critical-contributions-aggregate-without-double-roll-test
  (let [dim {:id :dim-folding-theorem
             :activation :passive
             :reactions [{:on :combat/damage
                          :priority 200
                          :program {:component :damage/critical
                                    :levels [{:level 0 :probability 0.0
                                              :multiplier {:tunable :damage-multipliers
                                                           :path [0]}}
                                             {:level 1 :probability 0.0
                                              :multiplier {:tunable :damage-multipliers
                                                           :path [1]}}
                                             {:level 2 :probability 0.0
                                              :multiplier {:tunable :damage-multipliers
                                                           :path [2]}}]
                                    :damage-types [:magic]
                                    :exp-per-level 0.005}}]}
        space {:id :space-fluct
               :activation :passive
               :reactions [{:on :combat/damage
                            :priority 200
                            :program {:component :damage/critical
                                      :levels [{:level 0 :probability 0.0}
                                               {:level 1 :probability 0.0}
                                               {:level 2 :probability 1.0}]
                                      :damage-types [:magic]
                                      :exp-per-level 0.0001
                                      :exp-mode :fixed
                                      :events-by-level
                                      {:level-2 [{:type :achievement/trigger
                                                  :payload {:id "teleporter.mastery"}}]}}}]}
        request {:source "attacker"
                 :target "target"
                 :base 10.0
                 :type :magic
                 :components {:direct 10.0}
                 :tags #{:combat}
                 :metadata {:activation-seed 7}}
        state (fn [_]
                {:ability-data {:learned-skills #{:dim-folding-theorem :space-fluct}
                                :skill-exps {}}})
        result (reactions/apply!
                request
                {:reactions [dim space]
                 :session-fn (constantly nil)
                 :state-fn state
                 :tunables-fn (fn [id _ _]
                                (if (= id :dim-folding-theorem)
                                  {:damage-multipliers [1.3 1.6 2.6]}
                                  {}))
                 :domain-state {}})]
    (is (= 26.0 (:base result)))
    (is (= 26.0 (get-in result [:components :direct])))
    (is (= 2 (get-in result [:metadata :critical :level])))
    (is (= [{:path [:ability-data :skill-exps :dim-folding-theorem]
             :mode :increment :value 0.015}
            {:path [:ability-data :skill-exps :space-fluct]
             :mode :increment :value 0.0001}]
           (:source-state-patch result)))
    (is (= 1 (count (filter #(= "teleporter.mastery"
                                (get-in % [:payload :id]))
                            (:events result)))))))

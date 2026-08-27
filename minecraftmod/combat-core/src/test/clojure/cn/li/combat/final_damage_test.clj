(ns cn.li.combat.final-damage-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.combat.final-damage :as damage]
            [cn.li.mcmod.runtime.damage-boundary :as boundary]))
(deftest fixed-order-damage-resolution-test
  (let [result (damage/resolve-event [{:ability-id :a :reaction-id :m :priority 1 :match {:types #{:skill}}
                                       :contributions [{:kind :multiplier :value 2.0}
                                                       {:kind :reduction :value 0.25}
                                                       {:kind :absorption :value 1.0}]}]
                                {:world-id "w" :source :a :target :b :base 10 :type :skill :seed 4})]
    (is (= 14.0 (:amount result)))
    (is (= 1 (count (:matched result))))))
(deftest reflection-depth-is-bounded-test
  (let [reaction {:ability-id :reflector :reaction-id :r :match {:types #{:skill}}
                  :contributions [{:kind :reflection :ratio 1.0}]}
        result (damage/resolve-event [reaction] {:world-id "w" :source :a :target :b :base 2 :type :skill :depth 8})]
    (is (empty? (:reflections result)))))

(deftest lowered-policy-program-is-executed-test
  (let [result (damage/resolve-event
                [{:ability-id :shield :reaction-id :absorb :priority 10
                  :on :combat/damage
                  :when {:expr :math/gt :args [{:ref [:request :base]} 0.0]}
                  :program {:component :damage/absorb :cap 3.0}}]
                {:world-id "w" :source :a :target :b :base 10 :type :skill :seed 4})]
    (is (= 7.0 (:amount result)))
    (is (= [{:ability-id :shield :reaction-id :absorb}]
           (:matched result)))))
(deftest reduction-ignore-threshold-test
  (let [reaction {:ability-id :deviation :reaction-id :reduce :priority 1
                  :on :combat/damage
                  :program {:component :damage/reduce :rate 0.5 :max-cost 99.0 :ignore-threshold 5.0}}
        small (damage/resolve-event [reaction] {:world-id "w" :source :a :target :b :base 4.0 :type :skill :seed 1})
        large (damage/resolve-event [reaction] {:world-id "w" :source :a :target :b :base 6.0 :type :skill :seed 1})]
    (is (= 2.0 (:amount small)))
    (is (= 6.0 (:amount large)))
    (is (= 2.0 (get-in small [:resource-costs :cp])))
    (is (nil? (get-in large [:resource-costs :cp])))))
(deftest boundary-commits-only-after-actual-apply-test
  (let [committed (atom nil)]
    (damage/install-boundary! {:reactions [] :commit-state! #(reset! committed %)})
    (let [resolution (boundary/begin! {:world-id "w" :source :a :target :b :base 2 :type :skill})]
      (boundary/complete! resolution false 0.0)
      (is (nil? @committed))
      (boundary/complete! resolution true 2.0)
      (is (= 2.0 (:amount @committed))))
    (boundary/clear!)))
(deftest absorb-interval-and-session-patch-test
  (let [reaction {:ability-id :shield :reaction-id :absorb :priority 10
                  :on :combat/damage
                  :program {:component :damage/absorb :cap 3.0
                            :interval-ticks 10 :last-tick-path [:last-absorb-tick]}}
        base-event {:world-id "w" :source :a :target :b :base 10 :type :skill
                    :metadata {:input {:context {:resources {:cp 100.0 :overload 100.0}}
                                        :session {:last-absorb-tick 15}}}}
        blocked (damage/resolve-event [reaction] (assoc base-event :seed 20))
        applied (damage/resolve-event [reaction] (assoc base-event :seed 25))]
    (is (= 10.0 (:amount blocked)))
    (is (= 7.0 (:amount applied)))
    (is (= [{:path [:last-absorb-tick] :mode :assign :value 25}]
           (:session-patches applied)))
    (is (empty? (:session-patches blocked)))))
(deftest absorb-exp-tag-emits-progression-even-when-payment-fails-test
  (let [reaction {:ability-id :light-shield :reaction-id :absorb :priority 10
                  :on :combat/damage
                  :program {:component :damage/absorb :cap 3.0
                            :cost {:cp 10.0}
                            :exp-tag :attacked
                            :exp-scale 0.25}}
        result (damage/resolve-event [reaction]
                                      {:world-id "w" :source :a :target :b
                                       :base 10.0 :type :skill :seed 4
                                       :metadata {:input {:context {:resources {:cp 0.0
                                                                                :overload 0.0}}}}})]
    (is (= 10.0 (:amount result)))
    (is (= [{:type :score/mark :tag :attacked :progression 0.25
             :owner nil :ability-id :light-shield}]
           (:side-events result)))))(deftest absorb-front-cone-is-enforced-test
  (let [reaction {:ability-id :shield :reaction-id :absorb :priority 10
                  :on :combat/damage
                  :program {:component :damage/absorb :cap 3.0 :front? false}}
        result (damage/resolve-event [reaction]
                                      {:world-id "w" :source :a :target :b :base 10
                                       :type :skill :seed 4
                                       :metadata {:input {:context {:resources {:cp 100.0
                                                                                :overload 100.0}}}}})]
    (is (= 10.0 (:amount result)))
    (is (empty? (:session-patches result)))))
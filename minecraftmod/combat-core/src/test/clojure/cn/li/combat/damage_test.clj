(ns cn.li.combat.damage-test
  "Reuses several of cn.li.combat.final-damage-test's exact scenarios
   (adapted for the new build-index/resolve-event API: index instead of a
   raw reaction collection) as a direct confidence check that the ported
   aggregation algorithm behaves identically to the still-live old one --
   these are pre-existing, already-validated fixtures, not newly invented
   ones. New coverage (not in final_damage_test.clj) is the mark-type
   index itself: build-index/candidates-for."
  (:require [clojure.test :refer [deftest is]]
            [cn.li.combat.damage :as damage]
            [cn.li.mcmod.runtime.damage-boundary :as boundary]))

(deftest fixed-order-damage-resolution-test
  (let [index (damage/build-index [{:ability-id :a :reaction-id :m :priority 1 :match {:types #{:skill}}
                                    :contributions [{:kind :multiplier :value 2.0}
                                                    {:kind :reduction :value 0.25}
                                                    {:kind :absorption :value 1.0}]}])
        result (damage/resolve-event index {:world-id "w" :source :a :target :b :base 10 :type :skill :seed 4})]
    (is (= 14.0 (:amount result)))
    (is (= 1 (count (:matched result))))))

(deftest reflection-depth-is-bounded-test
  (let [index (damage/build-index [{:ability-id :reflector :reaction-id :r :match {:types #{:skill}}
                                    :contributions [{:kind :reflection :ratio 1.0}]}])
        result (damage/resolve-event index {:world-id "w" :source :a :target :b :base 2 :type :skill :depth 8})]
    (is (empty? (:reflections result)))))

(deftest lowered-policy-program-is-executed-test
  (let [index (damage/build-index
               [{:ability-id :shield :reaction-id :absorb :priority 10
                 :on :combat/damage
                 :when {:expr :math/gt :args [{:ref [:request :base]} 0.0]}
                 :program {:component :damage/absorb :cap 3.0}}])
        result (damage/resolve-event index {:world-id "w" :source :a :target :b :base 10 :type :skill :seed 4})]
    (is (= 7.0 (:amount result)))
    (is (= [{:ability-id :shield :reaction-id :absorb}] (:matched result)))))

(deftest reduction-ignore-threshold-test
  (let [index (damage/build-index
               [{:ability-id :deviation :reaction-id :reduce :priority 1
                 :on :combat/damage
                 :program {:component :damage/reduce :rate 0.5 :max-cost 99.0 :ignore-threshold 5.0
                           :cost-resource :cp}}])
        small (damage/resolve-event index {:world-id "w" :source :a :target :b :base 4.0 :type :skill :seed 1})
        large (damage/resolve-event index {:world-id "w" :source :a :target :b :base 6.0 :type :skill :seed 1})]
    (is (= 2.0 (:amount small)))
    (is (= 6.0 (:amount large)))
    (is (= 2.0 (get-in small [:resource-costs :cp])))
    (is (nil? (get-in large [:resource-costs :cp])))))

(deftest absorb-interval-and-session-patch-test
  (let [index (damage/build-index
               [{:ability-id :shield :reaction-id :absorb :priority 10
                 :on :combat/damage
                 :program {:component :damage/absorb :cap 3.0
                           :interval-ticks 10 :last-tick-path [:last-absorb-tick]}}])
        base-event {:world-id "w" :source :a :target :b :base 10 :type :skill
                    :metadata {:input {:context {:resources {:cp 100.0 :overload 100.0}}
                                        :session {:last-absorb-tick 15}}}}
        blocked (damage/resolve-event index (assoc base-event :seed 20))
        applied (damage/resolve-event index (assoc base-event :seed 25))]
    (is (= 10.0 (:amount blocked)))
    (is (= 7.0 (:amount applied)))
    (is (= [{:path [:last-absorb-tick] :mode :assign :value 25}] (:session-patches applied)))
    (is (empty? (:session-patches blocked)))))

(deftest reduce-without-cost-resource-throws-when-cost-is-positive-test
  (let [index (damage/build-index
               [{:ability-id :deviation :reaction-id :reduce :priority 1
                 :on :combat/damage
                 :program {:component :damage/reduce :rate 0.5 :max-cost 99.0 :ignore-threshold 99.0}}])]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cost-resource"
          (damage/resolve-event index {:world-id "w" :source :a :target :b :base 4.0 :type :skill :seed 1})))))

(deftest reduce-is-resource-agnostic-test
  (let [index (damage/build-index
               [{:ability-id :bc-ritual :reaction-id :reduce :priority 1
                 :on :combat/damage
                 :program {:component :damage/reduce :rate 0.5 :max-cost 99.0 :ignore-threshold 99.0
                           :cost-resource :mana}}])
        result (damage/resolve-event index {:world-id "w" :source :a :target :b :base 4.0 :type :skill :seed 1})]
    (is (= 2.0 (get-in result [:resource-costs :mana])))
    (is (nil? (get-in result [:resource-costs :cp])))))

(deftest boundary-commits-only-after-actual-apply-test
  (let [committed (atom nil)]
    (damage/install-boundary! {:reactions [] :commit-state! #(reset! committed %)})
    (let [resolution (boundary/begin! {:world-id "w" :source :a :target :b :base 2 :type :skill})]
      (boundary/complete! resolution false 0.0)
      (is (nil? @committed))
      (boundary/complete! resolution true 2.0)
      (is (= 2.0 (:amount @committed))))
    (boundary/clear!)))

(deftest absorb-exp-tag-emits-progression-even-when-payment-fails-test
  (let [index (damage/build-index
               [{:ability-id :light-shield :reaction-id :absorb :priority 10
                 :on :combat/damage
                 :program {:component :damage/absorb :cap 3.0
                           :cost {:cp 10.0}
                           :progression-tag :attacked
                           :progression-scale 0.25}}])
        result (damage/resolve-event index
                                     {:world-id "w" :source :a :target :b
                                      :base 10.0 :type :skill :seed 4
                                      :metadata {:input {:context {:resources {:cp 0.0
                                                                               :overload 0.0}}}}})]
    (is (= 10.0 (:amount result)))
    (is (= [{:type :score/mark :tag :attacked :progression 0.25
             :owner nil :ability-id :light-shield}]
           (:side-events result)))))

(deftest absorb-front-cone-is-enforced-test
  (let [index (damage/build-index
               [{:ability-id :shield :reaction-id :absorb :priority 10
                 :on :combat/damage
                 :program {:component :damage/absorb :cap 3.0 :front? false}}])
        result (damage/resolve-event index
                                     {:world-id "w" :source :a :target :b :base 10
                                      :type :skill :seed 4
                                      :metadata {:input {:context {:resources {:cp 100.0
                                                                               :overload 100.0}}}}})]
    (is (= 10.0 (:amount result)))
    (is (empty? (:session-patches result)))))

(deftest reduce-exp-tag-emits-only-for-eligible-damage-test
  (let [index (damage/build-index
               [{:ability-id :vec-deviation :reaction-id :reduce :priority 1
                 :on :combat/damage
                 :program {:component :damage/reduce :rate 0.5 :max-cost 99.0
                           :ignore-threshold 5.0 :cost-resource :cp
                           :progression-tag :damaged :progression-scale 0.1}}])
        eligible (damage/resolve-event index {:world-id "w" :source :a :target :b :base 4.0 :type :skill :seed 1})
        ignored (damage/resolve-event index {:world-id "w" :source :a :target :b :base 6.0 :type :skill :seed 1})]
    (is (= [{:type :score/mark :tag :damaged :progression 0.1
             :owner nil :ability-id :vec-deviation}] (:side-events eligible)))
    (is (empty? (:side-events ignored)))))

(deftest reflect-exp-tag-emits-on-eligible-reflection-test
  (let [index (damage/build-index
               [{:ability-id :vec-reflection :reaction-id :reflect :priority 1
                 :on :combat/damage
                 :program {:component :damage/reflect :multiplier 0.5 :minimum 0.0
                           :max-depth 5 :cost-per-damage 0.0
                           :progression-tag :damaged :progression-scale 0.2}}])
        result (damage/resolve-event index {:world-id "w" :source :a :target :b :base 10.0 :type :skill :seed 1})]
    (is (= [{:type :score/mark :tag :damaged :progression 0.2
             :owner nil :ability-id :vec-reflection}] (:side-events result)))
    (is (= 1 (count (:reflections result))))))

;; --- new coverage: the mark-type + priority index itself --------------------

(deftest build-index-buckets-by-mark-type-and-universal-test
  (let [radiation {:ability-id :a :reaction-id :r1 :mark-type :radiation :priority 5
                   :match {:types #{:skill}} :contributions [{:kind :multiplier :value 2.0}]}
        poison {:ability-id :b :reaction-id :r2 :mark-type :poison :priority 1
               :match {:types #{:skill}} :contributions [{:kind :multiplier :value 3.0}]}
        no-mark {:ability-id :c :reaction-id :r3 :priority 1
                 :match {:types #{:skill}} :contributions [{:kind :reduction :value 0.1}]}
        index (damage/build-index [radiation poison no-mark])]
    (is (= [radiation] (get-in index [:by-mark-type :radiation])))
    (is (= [poison] (get-in index [:by-mark-type :poison])))
    (is (= [no-mark] (:universal index)))))

(deftest candidates-for-narrows-by-event-mark-type-but-keeps-universal-test
  (let [radiation {:ability-id :a :reaction-id :r1 :mark-type :radiation :priority 5
                   :match {:types #{:skill}} :contributions []}
        poison {:ability-id :b :reaction-id :r2 :mark-type :poison :priority 1
               :match {:types #{:skill}} :contributions []}
        no-mark {:ability-id :c :reaction-id :r3 :priority 1
                 :match {:types #{:skill}} :contributions []}
        index (damage/build-index [radiation poison no-mark])
        radiation-event {:world-id "w" :source :a :target :b :base 1 :type :skill
                         :metadata {:input {:context {:mark-type :radiation}}}}
        candidates (damage/candidates-for index (damage/event radiation-event))]
    (is (= #{radiation no-mark} (set candidates)))
    (is (not (contains? (set candidates) poison)))))

(deftest mark-type-policy-only-applies-to-matching-events-test
  (let [radiation {:ability-id :a :reaction-id :r1 :mark-type :radiation :priority 5
                   :on :combat/damage :match {:types #{:skill}}
                   :contributions [{:kind :multiplier :value 2.0}]}
        index (damage/build-index [radiation])
        with-mark (damage/resolve-event index
                                        {:world-id "w" :source :a :target :b :base 10 :type :skill
                                         :metadata {:input {:context {:mark-type :radiation}}}})
        without-mark (damage/resolve-event index
                                           {:world-id "w" :source :a :target :b :base 10 :type :skill})]
    (is (= 20.0 (:amount with-mark)) "radiation-marked target is doubled")
    (is (= 10.0 (:amount without-mark)) "unmarked target is untouched by the radiation policy")))

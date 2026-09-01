(ns cn.li.combat.damage-math-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.combat.damage-math :as damage-math]))

(deftest compute-aoe-damage-falls-off-linearly-with-distance-test
  (is (= 100.0 (damage-math/compute-aoe-damage {:x 0.0 :y 0.0 :z 0.0} {:x 0.0 :y 0.0 :z 0.0} 10.0 100.0 true)))
  (is (= 50.0 (damage-math/compute-aoe-damage {:x 0.0 :y 0.0 :z 0.0} {:x 5.0 :y 0.0 :z 0.0} 10.0 100.0 true)))
  (is (= 0.0 (damage-math/compute-aoe-damage {:x 0.0 :y 0.0 :z 0.0} {:x 20.0 :y 0.0 :z 0.0} 10.0 100.0 true))))

(deftest compute-aoe-damage-without-falloff-is-flat-within-radius-test
  (is (= 100.0 (damage-math/compute-aoe-damage {:x 0.0 :y 0.0 :z 0.0} {:x 5.0 :y 0.0 :z 0.0} 10.0 100.0 false)))
  (is (= 0.0 (damage-math/compute-aoe-damage {:x 0.0 :y 0.0 :z 0.0} {:x 20.0 :y 0.0 :z 0.0} 10.0 100.0 false))))

(deftest select-next-reflection-target-picks-nearest-within-radius-excluding-self-test
  (let [candidates [{:entity-uuid "self" :x 0.0 :y 0.0 :z 0.0}
                    {:entity-uuid "far" :x 50.0 :y 0.0 :z 0.0}
                    {:entity-uuid "near" :x 3.0 :y 0.0 :z 0.0}]]
    (is (= "near" (damage-math/select-next-reflection-target "self" {:x 0.0 :y 0.0 :z 0.0} candidates 10.0)))
    (is (nil? (damage-math/select-next-reflection-target "self" {:x 0.0 :y 0.0 :z 0.0} candidates 2.0)))))

(deftest compute-reflected-damage-applies-multiplier-test
  (is (= 25.0 (damage-math/compute-reflected-damage 100.0 0.25))))

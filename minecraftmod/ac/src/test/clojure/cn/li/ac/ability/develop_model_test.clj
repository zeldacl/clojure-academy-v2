(ns cn.li.ac.ability.develop-model-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.ability.config :as cfg]
            [cn.li.ac.ability.model.develop :as develop]))

(deftest develop-core-edge-test
  (let [d0 (develop/new-develop-data)
        d1 (develop/start-develop d0 :normal :learn-skill {:skill-id :s1} 2)
        t1 (develop/tick-develop d1)]
    (is (develop/idle? d0))
    (is (develop/developing? d1))
    (is (= :normal (:developer-type d1)))
    (is (> (:energy-consumed t1) 0.0))
    (is (= d0 (develop/tick-develop d0)))
    (is (= 0.0 (develop/progress d0)))
    (is (= (int (Math/ceil (+ 3.0 (* 4 4 0.5)))) (develop/skill-learning-stims 4)))
    (is (= 15 (develop/level-up-stims 2)))))

(deftest develop-formulas-use-config-test
  (with-redefs [cfg/skill-learning-cost-base (constantly 4.0)
                cfg/skill-learning-cost-level-square-factor (constantly 1.0)
                cfg/level-up-stim-base (constantly 7)]
    (is (= 13 (develop/skill-learning-stims 3)))
    (is (= 21 (develop/level-up-stims 2)))))

(deftest develop-contract-test
  (let [d1 (develop/start-develop (develop/new-develop-data) :advanced :level-up {:target-level 2} 1)
        ;; advanced tps = 15, exactly 15 ticks should finish one stim
        d2 (nth (iterate develop/tick-develop d1) 15)
        d3 (develop/fail d1)
        d4 (develop/abort d1)
        d5 (develop/complete-and-reset d2)]
    (is (develop/done? d2))
    (is (= 1 (:stim d2)))
    (is (= 1.0 (develop/progress d2)))
    (is (develop/failed? d3))
    (is (develop/idle? d4))
    (is (develop/idle? d5))
    (is (= 0 (:stim d5)))))

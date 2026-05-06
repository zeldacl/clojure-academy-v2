(ns cn.li.ac.ability.cooldown-model-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.ability.model.cooldown :as cooldown]))

(deftest cooldown-core-edge-test
  (let [d0 (cooldown/new-cooldown-data)
        d1 (cooldown/set-cooldown d0 :cat :main 10)
        d2 (cooldown/set-cooldown d1 :cat :main 3)
        d3 (cooldown/set-cooldown d2 :cat :sub 1)
        d4 (cooldown/tick-cooldowns d3)]
    (is (cooldown/in-cooldown? d1 :cat :main))
    (is (= 10 (cooldown/get-remaining d2 :cat :main)))
    (is (= 9 (cooldown/get-remaining d4 :cat :main)))
    (is (= 0 (cooldown/get-remaining d4 :cat :sub)))
    (is (= 0 (cooldown/get-remaining d4 :none :none)))))

(deftest cooldown-contract-test
  (let [d {[:a :main] 5 [:b :x] 2}
        v (cooldown/cooldown-data->vec d)
        roundtrip (cooldown/vec->cooldown-data v)]
    (is (= d roundtrip))
    (is (= {[:a :main] 5} (cooldown/tick-cooldowns {[:a :main] 6 :bad 1})))
    (let [decoded (cooldown/vec->cooldown-data [["a" "main" 1]])]
      (is (= {[:a :main] 1} decoded)))))

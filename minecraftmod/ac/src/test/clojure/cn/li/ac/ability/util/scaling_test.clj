(ns cn.li.ac.ability.util.scaling-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [cn.li.ac.ability.util.scaling :as sc]))

(deftest lerp-endpoints-test
  (is (= 10.0 (sc/lerp 10.0 20.0 0.0)))
  (is (= 20.0 (sc/lerp 10.0 20.0 1.0)))
  (is (= 15.0 (sc/lerp 10.0 20.0 0.5))))

(deftest lerp-extrapolation-test
  (is (= 5.0 (sc/lerp 10.0 20.0 -0.5)))
  (is (= 25.0 (sc/lerp 10.0 20.0 1.5))))

(deftest scale-damage-range-duration-test
  (is (= 7.0 (sc/scale-damage 5.0 10.0 0.4)))
  (is (= 12.0 (sc/scale-range 10.0 15.0 0.4)))
  (is (= 12 (sc/scale-duration 10 20 0.2))))

(deftest scale-cooldown-inverse-test
  (is (= 100 (sc/scale-cooldown 100 50 0.0)))
  (is (= 50 (sc/scale-cooldown 100 50 1.0)))
  (is (= 75 (sc/scale-cooldown 100 50 0.5))))

(deftest scale-cost-inverse-test
  (is (= 10.0 (sc/scale-cost 10.0 5.0 0.0)))
  (is (= 5.0 (sc/scale-cost 10.0 5.0 1.0))))

(defspec lerp-t-in-01-bounded-property-test
  100
  (prop/for-all [min-v (gen/double* {:infinite? false :NaN? false})
                 max-v (gen/double* {:infinite? false :NaN? false})
                 t (gen/double* {:min 0.0 :max 1.0 :infinite? false :NaN? false})]
                (let [lo (min min-v max-v)
                      hi (max min-v max-v)
                      v (sc/lerp min-v max-v t)]
                  (and (<= lo v) (<= v hi)))))

(ns cn.li.combat.targeting-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.combat.targeting :as targeting]))

(deftest marcher-crosses-wall-and-waits-for-clearance
  (let [result (targeting/march-through-collision
                {:x 0.0 :y 0.0 :z 0.0}
                {:x 1.0 :y 0.0 :z 0.0}
                8.0 0.8 4
                (fn [x _y _z] (<= 2 x 3)))]
    (is (:available? result))
    (is (< (Math/abs (- 8.0 (double (:distance result)))) 1.0e-9))
    (is (< (Math/abs (- 8.0 (double (get-in result [:position :x])))) 1.0e-9))
    (is (= {:y 0.0 :z 0.0} (select-keys (:position result) [:y :z])))))

(deftest marcher-reports-inside-wall-as-unavailable
  (let [result (targeting/march-through-collision
                {:x 0.0 :y 0.0 :z 0.0}
                {:x 1.0 :y 0.0 :z 0.0}
                2.0 0.8 4
                (fn [x _y _z] (<= 1 x)))]
    (is (false? (:available? result)))
    (is (false? (:valid? result)))))

(deftest marcher-uses-truncation-toward-zero
  (let [seen (atom [])]
    (targeting/march-through-collision
     {:x -0.4 :y 0.0 :z 0.0}
     {:x 1.0 :y 0.0 :z 0.0}
     0.0 0.8 4
     (fn [x y z]
       (swap! seen conj [x y z])
       false))
    (is (= [0 0 0] (first @seen)))))

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

(deftest directional-destination-preserves-feet-to-eye-ray
  (let [calls (atom [])
        result (targeting/directional-destination
                {:origin {:x 0.0 :y 10.0 :z 0.0}
                 :look {:x 0.0 :y 0.0 :z 1.0}
                 :eye-y 11.62
                 :direction :forward
                 :distance 12.0
                 :raycast (fn [& args] (reset! calls args) nil)})]
    (is (:valid? result))
    (is (= {:x 0.0 :y 10.0 :z 0.0} (:from result)))
    (is (= {:x 0.0 :y 11.62 :z 12.0} (:position result)))
    (is (= 7 (count @calls)))
    (is (< (double (nth @calls 6)) 13.0))))

(deftest directional-destination-applies-side-face-and-head-clearance
  (let [result (targeting/directional-destination
                {:origin {:x 0.0 :y 10.0 :z 0.0}
                 :look {:x 0.0 :y 0.0 :z 1.0}
                 :eye-y 11.62
                 :direction :forward
                 :distance 12.0
                 :raycast (fn [& _]
                            {:hit-type :block :hit-x 0.0 :hit-y 11.0 :hit-z 4.0
                             :face :north})
                 :head-blocked? (fn [_ _ _] true)})]
    (is (= 11.45 (double (get-in result [:position :y]))))
    (is (= 3.4 (double (get-in result [:position :z]))))))

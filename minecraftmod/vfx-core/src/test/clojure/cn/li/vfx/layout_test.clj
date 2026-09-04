(ns cn.li.vfx.layout-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.vfx.layout :as layout]))

(deftest build-splits-float-and-int-attributes-into-separate-column-ranges-test
  ;; build processes attrs in sorted-by-key order (see the determinism
  ;; test below for why): :age, :color, :position, :velocity.
  (let [l (layout/build {:position :vec3 :velocity :vec3 :age :float :color :color} 512)]
    (testing "scalar float :age is processed first alphabetically, gets column 0"
      (is (= [0] (layout/column l :age))))
    (testing "color is packed into the INT range, its OWN 0-based sequence, not continuing the float range"
      (is (= [0] (layout/column l :color))))
    (testing "vec3 gets 3 consecutive columns each, continuing the float range after :age's 1 column"
      (is (= [1 2 3] (layout/column l :position)))
      (is (= [4 5 6] (layout/column l :velocity))))
    (testing "column counts"
      (is (= 7 (:float-cols l)))
      (is (= 1 (:int-cols l))))))

(deftest unused-attributes-are-dead-stripped-by-never-being-requested-test
  (let [l (layout/build {:position :vec3} 512)]
    (is (= [0 1 2] (layout/column l :position)))
    (is (nil? (layout/column l :velocity)) "an attribute no module reads/writes was never passed to build at all")
    (is (= 3 (:float-cols l)) "no columns reserved for anything beyond what was actually requested")))

(deftest build-is-deterministic-across-equal-attribute-sets-test
  (testing "column assignment does not depend on map iteration order -- two calls
            with the same attrs (built in different literal order) must agree,
            since compiled module closures capture these offsets once at compile
            time and must mean the same thing on every machine that compiles them"
    (let [a (layout/build {:position :vec3 :age :float :color :color} 256)
          b (layout/build {:color :color :age :float :position :vec3} 256)]
      (is (= a b)))))

(deftest offset-is-column-major-test
  (let [l (layout/build {:age :float} 100)
        [col] (layout/column l :age)]
    (is (= 0 (layout/offset l col 0)))
    (is (= 42 (layout/offset l col 42)))
    (is (= 100 (layout/offset l 1 0)) "the next column starts exactly `capacity` slots later")))

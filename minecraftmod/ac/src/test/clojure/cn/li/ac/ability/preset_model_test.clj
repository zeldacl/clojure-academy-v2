(ns cn.li.ac.ability.preset-model-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.ability.model.preset :as preset]))

(deftest preset-core-edge-test
  (let [d0 (preset/new-preset-data)
        d1 (preset/set-active-preset d0 2)
        d2 (preset/set-slot d1 2 0 [:cat :ctrl-a])
        d3 (preset/set-slot d2 2 1 [:cat :ctrl-b])
        d4 (preset/set-slot d3 2 1 nil)]
    (is (= 0 (preset/get-active-preset d0)))
    (is (= 2 (preset/get-active-preset d1)))
    (is (= [:cat :ctrl-a] (preset/get-slot d3 2 0)))
    (is (= nil (preset/get-slot d4 2 1)))
    (is (= [[:cat :ctrl-a] nil nil nil] (preset/get-active-slots d4)))
    (is (thrown? IllegalArgumentException (preset/set-active-preset d0 -1)))
    (is (thrown? IllegalArgumentException (preset/set-active-preset d0 4)))))

(deftest preset-serialization-contract-test
  (let [d {:active-preset 3
           :slots {[3 0] [:ac :x]
                   [3 1] [:ac :y]}}
        v (preset/preset-data->vec d)
        r (preset/vec->preset-data v 3)]
    (is (= 3 (:active-preset r)))
    (is (= [:ac :x] (get-in r [:slots [3 0]])))
    (is (= [:ac :y] (get-in r [:slots [3 1]])))
    (is (= d r))))

(deftest preset-slots-survive-list-pairs-and-long-keys-test
  (let [d {:active-preset 0
           :slots {[(long 0) (long 0)] (list :electromaster :railgun)
                   [0 1] [:electromaster :arc-gen]}}]
    (is (= [:electromaster :railgun] (preset/get-slot d 0 0)))
    (is (= [:electromaster :arc-gen] (preset/get-slot d 0 1)))
    (is (= [[:electromaster :railgun] [:electromaster :arc-gen] nil nil]
           (preset/get-active-slots d)))))

(deftest set-slot-clears-long-keys-after-nbt-shaped-load-test
  "After NBT reload slot keys are Longs; clearing must not leave a ghost entry."
  (let [loaded {:active-preset (long 0)
                :slots {[(long 0) (long 0)] [:electromaster :railgun]}}
        cleared (preset/set-slot loaded 0 0 nil)
        rebound (preset/set-slot loaded 0 0 [:electromaster :arc-gen])]
    (is (nil? (preset/get-slot cleared 0 0)))
    (is (= {} (:slots cleared)))
    (is (= [:electromaster :arc-gen] (preset/get-slot rebound 0 0)))
    (is (= {[0 0] [:electromaster :arc-gen]} (:slots rebound)))
    (is (= 0 (:active-preset (preset/normalize-preset-data loaded))))))


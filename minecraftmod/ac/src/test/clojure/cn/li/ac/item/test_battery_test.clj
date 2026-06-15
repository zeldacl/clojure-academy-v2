(ns cn.li.ac.item.test-battery-test
  (:require [clojure.test :refer [deftest is testing are]]
            [cn.li.ac.energy.imag-energy-item :as ie]
            [cn.li.ac.test.support.nbt :as test-nbt]
            [cn.li.ac.item.test-battery :as tb]
            [cn.li.mcmod.platform.item :as item]
            [cn.li.mcmod.platform.nbt :as nbt]))

(defn- fake-stack [registry-id tag-compound]
  {:reg registry-id :tag tag-compound})

(deftest create-battery-test
  (let [b (tb/create-battery :energy-unit)]
    (is (satisfies? ie/ImagEnergyItem b))
    (is (= 10000.0 (ie/get-max-energy b)))
    (is (= 20.0 (ie/get-bandwidth b))))
  (is (thrown? IllegalArgumentException (tb/create-battery :nope))))

(deftest is-battery-by-registry-test
  (let [tag (test-nbt/atom-compound)
        stk (fake-stack "my_mod:energy_unit" tag)]
    (with-redefs [item/item-get-item identity
                  item/item-get-registry-name (fn [o] (:reg o))
                  item/item-get-tag-compound (fn [o] (:tag o))
                  item/item-get-or-create-tag (fn [o] (:tag o))]
      (is (true? (tb/is-battery? stk)))
      (is (= 10000.0 (tb/get-max-battery-energy stk))))))

(deftest is-battery-by-nbt-type-test
  (let [tag (test-nbt/atom-compound)
        _ (nbt/nbt-set-string! tag "batteryType" "energy-unit")
        stk (fake-stack "other:item" tag)]
    (with-redefs [item/item-get-item identity
                  item/item-get-registry-name (fn [o] (:reg o))
                  item/item-get-tag-compound (fn [o] (:tag o))
                  item/item-get-or-create-tag (fn [o] (:tag o))]
      (is (true? (tb/is-battery? stk))))))

(deftest set-and-clamp-energy-test
  (let [tag (test-nbt/atom-compound)
        stk (fake-stack "my_mod:developer_portable" tag)]
    (with-redefs [item/item-get-item identity
                  item/item-get-registry-name (fn [o] (:reg o))
                  item/item-get-tag-compound (fn [o] (:tag o))
                  item/item-get-or-create-tag (fn [o] (:tag o))]
      (tb/set-battery-energy! stk 5000.0)
      (is (= 5000.0 (nbt/nbt-get-double tag "energy")))
      (tb/set-battery-energy! stk -10.0)
      (is (= 0.0 (nbt/nbt-get-double tag "energy")))
      (tb/set-battery-energy! stk 9.99e9)
      (is (= 10000.0 (nbt/nbt-get-double tag "energy")))
      (is (= "developer-portable" (nbt/nbt-get-string tag "batteryType")))
    )
  )
)

(deftest charge-and-pull-behavior-test
  (let [tag (doto (test-nbt/atom-compound) (nbt/nbt-set-double! "energy" 0.0))
        stk (fake-stack "my_mod:energy_unit" tag)]
    (with-redefs [item/item-get-item identity
                  item/item-get-registry-name (fn [o] (:reg o))
                  item/item-get-tag-compound (fn [o] (:tag o))
                  item/item-get-or-create-tag (fn [o] (:tag o))]
      (are [amt ig leftover] (= leftover (tb/charge-battery! stk amt ig))
        100.0 false 80.0
        5.0 true 0.0)
      (is (= 25.0 (nbt/nbt-get-double tag "energy")))
      (is (= 20.0 (tb/pull-from-battery! stk 1000.0 false)))
      (is (= 5.0 (nbt/nbt-get-double tag "energy")))
      (is (= 5.0 (tb/pull-from-battery! stk 100.0 true)))
      (is (= 0.0 (nbt/nbt-get-double tag "energy")))
    )
  )
)

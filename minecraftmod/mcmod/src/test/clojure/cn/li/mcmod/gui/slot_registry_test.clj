(ns cn.li.mcmod.gui.slot-registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.mcmod.gui.slot-registry :as registry]
            [cn.li.mcmod.gui.slot-schema :as slot-schema]))

(deftest register-and-get-validator-test
  (testing "register-slot-validator! normalizes key to keyword"
    (reset! registry/slot-validators {})
    (let [validator (fn [_] true)]
      (is (nil? (registry/register-slot-validator! "energy" validator)))
      (is (identical? validator (registry/get-slot-validator :energy)))))
  (testing "unknown slot type returns nil"
    (is (nil? (registry/get-slot-validator :unknown)))))

(deftest slot-schema-delegation-test
  (with-redefs [slot-schema/tile-slot-count (fn [schema-id]
                                              (if (= schema-id :machine) 6 0))
                slot-schema/slot-type (fn [schema-id idx]
                                        (when (= schema-id :machine)
                                          ({0 :input 1 :output} idx)))]
    (testing "get-slot-count delegates to slot-schema"
      (is (= 6 (registry/get-slot-count :machine)))
      (is (= 0 (registry/get-slot-count :missing))))
    (testing "get-slot-type-for-index delegates to slot-schema"
      (is (= :input (registry/get-slot-type-for-index :machine 0)))
      (is (= :output (registry/get-slot-type-for-index :machine 1)))
      (is (nil? (registry/get-slot-type-for-index :machine 9))))))


(ns cn.li.block.wireless-node-test
  "Unit tests for wireless node pure logic in block namespace."
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.block.wireless-node.block :as wnode]
            [cn.li.ac.block.wireless-node.config :as node-config]))

(deftest node-types-source-of-truth-test
  (testing "node-types mirrors config contract"
    (is (= (node-config/node-types)
           (wnode/node-types))))

  (testing "all required tiers exist with positive max-energy"
    (let [tiers (wnode/node-types)]
      (doseq [tier [:basic :standard :advanced]]
        (is (contains? tiers tier))
        (is (pos? (get-in tiers [tier :max-energy])))))))

(deftest node-max-energy-test
  (testing "node-max-energy follows tier"
    (is (= (node-config/max-energy :basic)
           (wnode/node-max-energy {:node-type :basic})))
    (is (= (node-config/max-energy :standard)
           (wnode/node-max-energy {:node-type :standard})))
    (is (= (node-config/max-energy :advanced)
           (wnode/node-max-energy {:node-type :advanced}))))

  (testing "missing tier falls back to basic"
    (is (= (node-config/max-energy :basic)
           (wnode/node-max-energy {})))))

(deftest energy-to-blockstate-level-test
  (testing "energy maps to level range 0..4"
    (let [state {:node-type :basic}
          max-energy (double (wnode/node-max-energy state))]
      (is (= 0 (wnode/energy->blockstate-level 0.0 state)))
      (is (= 2 (wnode/energy->blockstate-level (/ max-energy 2.0) state)))
      (is (= 4 (wnode/energy->blockstate-level max-energy state)))
      (is (= 4 (wnode/energy->blockstate-level (* max-energy 2.0) state))))))

(defn run-all-tests []
  (clojure.test/run-tests 'cn.li.block.wireless-node-test))

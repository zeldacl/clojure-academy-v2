(ns cn.li.ac.block.wireless-node-test
  "Unit tests for wireless node pure logic."
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.block.wireless-node.inventory :as node-inventory]
            [cn.li.ac.block.wireless-node.logic :as wnode]
            [cn.li.ac.block.wireless-node.network-infra :as node-infra]
            [cn.li.ac.block.wireless-node.state :as node-state]
            [cn.li.ac.wireless.config :as node-config]
            [cn.li.ac.wireless.core.capability-resolver :as resolver])
  (:import [cn.li.acapi.wireless IWirelessNode]))

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

(deftest split-namespace-facade-test
  (testing "logic facade keeps existing public entry points"
    (is (identical? node-state/node-default-state wnode/node-default-state))
    (is (= node-state/node-state-schema wnode/node-state-schema))
    (is (= node-state/node-max-energy wnode/node-max-energy))
    (is (= node-inventory/node-container-fns wnode/node-container-fns))
    (is (fn? wnode/->WirelessNodeImpl))
    (is (fn? wnode/->ClojureEnergyImpl)))
  (testing "inventory namespace owns slot metadata"
    (node-inventory/ensure-node-slot-schema!)
    (is (= 2 (node-inventory/node-slot-count)))
    (is (= 0 (node-inventory/node-input-slot-index)))
    (is (= 1 (node-inventory/node-output-slot-index)))))

(deftest node-range-prefers-capability-range-test
  (let [cap (reify IWirelessNode
              (getEnergy [_] 0.0)
              (setEnergy [_ _] nil)
              (getMaxEnergy [_] 0.0)
              (getBandwidth [_] 0.0)
              (getCapacity [_] 0)
              (getRange [_] 42.5)
              (getNodeName [_] "")
              (getPassword [_] "")
              (getBlockPos [_] nil))]
    (testing "reads node range from resolved wireless capability"
      (with-redefs [resolver/node-capability (fn [_] cap)]
        (is (= 42.5 (node-infra/node-range :tile)))))
    (testing "falls back to default range when capability is absent"
      (with-redefs [resolver/node-capability (fn [_] nil)]
        (is (= 20.0 (node-infra/node-range :tile)))))))

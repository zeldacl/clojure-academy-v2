(ns cn.li.ac.block.wireless-node-test
  "Unit tests for wireless node pure logic."
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.energy.operations :as energy]
            [cn.li.ac.block.wireless-node.gui :as node-gui]
            [cn.li.ac.block.wireless-node.inventory :as node-inventory]
            [cn.li.ac.block.wireless-node.owner :as node-owner]
            [cn.li.ac.block.wireless-node.network-infra :as node-infra]
            [cn.li.ac.block.wireless-node.state :as node-state]
            [cn.li.ac.wireless.config :as node-config]
            [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.ac.wireless.core.capability-resolver :as resolver])
  (:import [cn.li.acapi.wireless IWirelessNode]))

(deftest node-types-source-of-truth-test
  (testing "node-types mirrors config contract"
    (is (= (node-config/node-types)
           (node-state/node-types))))

  (testing "all required tiers exist with positive max-energy"
    (let [tiers (node-state/node-types)]
      (doseq [tier [:basic :standard :advanced]]
        (is (contains? tiers tier))
        (is (pos? (get-in tiers [tier :max-energy])))))))

(deftest node-max-energy-test
  (testing "node-max-energy follows tier"
    (is (= (node-config/max-energy :basic)
           (node-state/node-max-energy {:node-type :basic})))
    (is (= (node-config/max-energy :standard)
           (node-state/node-max-energy {:node-type :standard})))
    (is (= (node-config/max-energy :advanced)
           (node-state/node-max-energy {:node-type :advanced}))))

  (testing "missing tier falls back to basic"
    (is (= (node-config/max-energy :basic)
           (node-state/node-max-energy {})))))

(deftest energy-to-blockstate-level-test
  (testing "energy maps to level range 0..4"
    (let [state {:node-type :basic}
          max-energy (double (node-state/node-max-energy state))]
      (is (= 0 (node-state/energy->blockstate-level 0.0 state)))
      (is (= 2 (node-state/energy->blockstate-level (/ max-energy 2.0) state)))
      (is (= 4 (node-state/energy->blockstate-level max-energy state)))
      (is (= 4 (node-state/energy->blockstate-level (* max-energy 2.0) state))))))

(deftest inventory-namespace-owns-slot-metadata-test
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

(deftest owner-authorization-compatibility-test
  (testing "blank owner keeps compatibility and allows edits"
    (is (true? (node-owner/owner-authorized? "" :player))))

  (testing "exact player name matches owner"
    (with-redefs [node-owner/player-name (fn [_] "alice")]
      (is (true? (node-owner/owner-authorized? "alice" :player)))
      (is (false? (node-owner/owner-authorized? "bob" :player)))))

  (testing "legacy serialized owner containing quoted player name is accepted"
    (with-redefs [node-owner/player-name (fn [_] "alice")]
      (is (true? (node-owner/owner-authorized? "ServerPlayer['alice'/1, l='world']" :player)))
      (is (false? (node-owner/owner-authorized? "ServerPlayer['bob'/1, l='world']" :player))))))

(deftest node-gui-slot-placement-policy-test
  (with-redefs [slot-schema/slot-type (fn [_ idx]
                                         (case idx
                                           0 :energy
                                           1 :output
                                           :unknown))
                energy/is-energy-item-supported? (fn [item] (= item :energy-item))]
    (testing "input slot accepts energy items only"
      (is (true? (node-gui/can-place-item? nil 0 :energy-item)))
      (is (false? (node-gui/can-place-item? nil 0 :ordinary-item))))

    (testing "output slot accepts energy items only"
      (is (true? (node-gui/can-place-item? nil 1 :energy-item)))
      (is (false? (node-gui/can-place-item? nil 1 :ordinary-item))))))

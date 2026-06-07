(ns cn.li.ac.block.wireless-node-gui-sync-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.block.wireless-node.gui :as node-gui]
            [cn.li.ac.block.wireless-node.logic :as node-logic]
            [cn.li.ac.wireless.gui.container.common :as common]))

(defn- sync-container-fixture
  [initial-rate]
  {:tile-entity :tile
   :transfer-rate (atom initial-rate)
   :energy (atom 0)
   :ssid (atom "")
   :password (atom "")
   :is-online (atom false)
   :placer-name (atom "")
   :node-type (atom :basic)
   :charging-in (atom false)
   :charging-out (atom false)})

(deftest create-container-normalizes-node-default-state-test
  (let [container (node-gui/create-container node-logic/node-default-state :player)]
    (is (= :node (:container-type container)))
    (is (= :basic @(:node-type container)))
    (is (= 15000 @(:max-energy container)))
    (is (= 0 @(:tab-index container)))))

(deftest update-derived-sync-fields-transfer-rate-test
  (testing "charging-in only maps to transfer rate 100"
    (let [container (sync-container-fixture 0)]
      (with-redefs [common/get-tile-state (fn [_] {:charging-in true :charging-out false})]
        (#'node-gui/update-derived-sync-fields! container)
        (is (= 100 @(:transfer-rate container))))))

  (testing "charging-in and charging-out map to transfer rate 200"
    (let [container (sync-container-fixture 0)]
      (with-redefs [common/get-tile-state (fn [_] {:charging-in true :charging-out true})]
        (#'node-gui/update-derived-sync-fields! container)
        (is (= 200 @(:transfer-rate container))))))

  (testing "no charging maps to transfer rate 0"
    (let [container (sync-container-fixture 100)]
      (with-redefs [common/get-tile-state (fn [_] {:charging-in false :charging-out false})]
        (#'node-gui/update-derived-sync-fields! container)
        (is (= 0 @(:transfer-rate container)))))))

(ns cn.li.ac.block.wireless-node-gui-sync-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.block.wireless-node.gui :as node-gui]
            [cn.li.ac.wireless.gui.sync.helpers :as sync-helpers]
            [cn.li.ac.wireless.gui.container.common :as common]))

(defn- sync-container-fixture
  [initial-rate]
  {:tile-entity :tile
   :transfer-rate (atom initial-rate)
   :sync-ticker (atom 0)
   :energy (atom 0)
   :ssid (atom "")
   :password (atom "")
   :is-online (atom false)
   :placer-name (atom "")
   :node-type (atom :basic)
   :charging-in (atom false)
   :charging-out (atom false)})

(deftest update-derived-sync-fields-transfer-rate-test
  (testing "charging-in only maps to transfer rate 100"
    (let [container (sync-container-fixture 0)
          queried (atom 0)]
      (with-redefs [common/get-tile-state (fn [_] {:charging-in true :charging-out false})
                    sync-helpers/with-throttled-sync! (fn [_ _ f] (f))
                    sync-helpers/query-node-network-capacity! (fn [_] (swap! queried inc))]
        (#'node-gui/update-derived-sync-fields! container)
        (is (= 100 @(:transfer-rate container)))
        (is (= 1 @queried)))))

  (testing "charging-in and charging-out map to transfer rate 200"
    (let [container (sync-container-fixture 0)]
      (with-redefs [common/get-tile-state (fn [_] {:charging-in true :charging-out true})
                    sync-helpers/with-throttled-sync! (fn [_ _ f] (f))
                    sync-helpers/query-node-network-capacity! (fn [_] nil)]
        (#'node-gui/update-derived-sync-fields! container)
        (is (= 200 @(:transfer-rate container))))))

  (testing "no charging maps to transfer rate 0"
    (let [container (sync-container-fixture 100)]
      (with-redefs [common/get-tile-state (fn [_] {:charging-in false :charging-out false})
                    sync-helpers/with-throttled-sync! (fn [_ _ f] (f))
                    sync-helpers/query-node-network-capacity! (fn [_] nil)]
        (#'node-gui/update-derived-sync-fields! container)
        (is (= 0 @(:transfer-rate container)))))))

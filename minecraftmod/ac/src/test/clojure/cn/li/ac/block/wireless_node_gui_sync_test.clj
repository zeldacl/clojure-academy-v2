(ns cn.li.ac.block.wireless-node-gui-sync-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cn.li.ac.block.wireless-node.gui :as node-gui]
            [cn.li.ac.block.wireless-node.state :as node-state]
            [cn.li.ac.wireless.gui.sync.helpers :as sync-helpers]
            [cn.li.ac.wireless.gui.container.common :as common]
            [cn.li.mcmod.gui.container-state :as container-state]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.platform.position :as pos]))

(use-fixtures
  :each
  (fn [f]
    (container-state/call-with-container-state-runtime
      (container-state/create-container-state-runtime)
      (fn []
        (try
          (f)
          (finally
            (container-state/clear-all!)))))))

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

(defn- routed-container
  [owner block-pos container-id]
  {:owner owner
   :container-id container-id
   :tile-entity {:block-pos block-pos}
   :energy (atom 0)})

(defn- with-position-stubs
  [f]
  (with-redefs [pos/position-get-block-pos (fn [tile] (:block-pos tile))
                pos/pos-x first
                pos/pos-y second
                pos/pos-z #(nth % 2)]
    (f)))

(deftest create-container-normalizes-node-default-state-test
  (let [container (node-gui/create-container node-state/node-default-state :player)]
    (is (= :node (:container-type container)))
    (is (= :basic @(:node-type container)))
    (is (= 15000 @(:max-energy container)))
    (is (= 0 @(:tab-index container)))))

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

(deftest sync-payload-routes-to-bound-owner-container-test
  (let [owner-a {:client-session-id :session-a :player-uuid "player-a"}
        owner-b {:client-session-id :session-a :player-uuid "player-b"}
        container-a (routed-container owner-a [1 2 3] 7)
        container-b (routed-container owner-b [1 2 3] 7)]
    (container-state/register-active-container! owner-a container-a)
    (container-state/register-active-container! owner-b container-b)

    (with-position-stubs
      #(binding [runtime-hooks/*client-session-id* :session-a
                 runtime-hooks/*player-state-owner* owner-a]
         (sync-helpers/apply-sync-payload-template! {:pos-x 1 :pos-y 2 :pos-z 3 :energy 42}
                                                   [:energy]
                                                   "node")))

    (is (= 42 @(:energy container-a)))
    (is (= 0 @(:energy container-b)))))

(deftest sync-payload-prefers-owner-scoped-container-id-test
  (let [owner-a {:client-session-id :session-a :player-uuid "player-a"}
        owner-b {:client-session-id :session-a :player-uuid "player-b"}
        container-a (routed-container owner-a [4 5 6] 17)
        container-b (routed-container owner-b [7 8 9] 17)]
    (container-state/register-active-container! owner-a container-a)
    (container-state/register-active-container! owner-b container-b)
    (container-state/register-container-by-id! owner-a 17 container-a)
    (container-state/register-container-by-id! owner-b 17 container-b)

    (binding [runtime-hooks/*client-session-id* :session-a
              runtime-hooks/*player-state-owner* owner-a]
      (sync-helpers/apply-sync-payload-template! {:container-id 17 :energy 99}
                                                [:energy]
                                                "node"))

    (is (= 99 @(:energy container-a)))
    (is (= 0 @(:energy container-b)))))

(deftest sync-payload-does-not-fallback-to-first-container-without-routing-clues-test
  (let [owner {:client-session-id :session-a :player-uuid "player-a"}
        container (routed-container owner [9 9 9] 99)]
    (container-state/register-active-container! owner container)

    (sync-helpers/apply-sync-payload-template! {:energy 7}
                                              [:energy]
                                              "node")

    (is (= 0 @(:energy container)))))

(deftest sync-payload-uses-unique-position-fallback-when-owner-is-unavailable-test
  (let [owner {:client-session-id :session-a :player-uuid "player-a"}
        container (routed-container owner [11 12 13] 21)]
    (container-state/register-active-container! owner container)

    (with-position-stubs
      #(sync-helpers/apply-sync-payload-template! {:pos-x 11 :pos-y 12 :pos-z 13 :energy 55}
                                                 [:energy]
                                                 "node"))

    (is (= 55 @(:energy container)))))

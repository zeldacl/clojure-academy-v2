(ns cn.li.ac.wireless.api-event-regression-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.test.support.wireless-stubs :as stubs]
            [cn.li.ac.wireless.api-query :as wireless-query]
            [cn.li.ac.wireless.api-command :as wireless-command]
            [cn.li.ac.wireless.service.world-registry :as world-registry]
            [cn.li.ac.wireless.service.network-command :as network-command]
            [cn.li.ac.wireless.core.vblock :as vb]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.events :as platform-events])
  (:import [cn.li.acapi.wireless
            WirelessCapabilityKeys]))

(deftest create-network-fires-topology-map-when-created
  (testing "create-network! posts map event when create succeeds"
    (let [events (atom [])
          matrix (stubs/fake-matrix)]
      (with-redefs [platform-be/be-get-world-safe (fn [_] :world)
            world-registry/get-world-data (fn [_] :world-data)
                    vb/create-vmatrix (fn [_] :matrix-vb)
            network-command/create-network! (fn [_ _ _ _] true)
                    platform-be/get-capability (fn [tile cap-key]
                                                 (when (and (= tile :matrix-tile)
                                                            (= cap-key WirelessCapabilityKeys/MATRIX))
                                                   matrix))
                    platform-events/fire-event! (fn [evt] (swap! events conj evt))]
        (is (true? (wireless-command/create-network! :matrix-tile "ssid-a" "pw")))
        (is (= 1 (count @events)))
        (let [e (first @events)]
          (is (= :topology/network (:kind e)))
          (is (= :created (:action e)))
          (is (= "ssid-a" (:ssid e)))
          (is (= matrix (:matrix e))))))))

(deftest create-network-no-event-when-not-created
  (let [events (atom [])]
    (with-redefs [platform-be/be-get-world-safe (fn [_] :world)
                  world-registry/get-world-data (fn [_] :world-data)
                  vb/create-vmatrix (fn [_] :matrix-vb)
                  network-command/create-network! (fn [_ _ _ _] false)
                  platform-be/get-capability (fn [_ _] (stubs/fake-matrix))
                  platform-events/fire-event! (fn [evt] (swap! events conj evt))]
      (is (false? (wireless-command/create-network! :matrix-tile "ssid-a" "pw")))
      (is (empty? @events)))))

(deftest destroy-network-fires-when-destroyed
  (let [events (atom [])
        matrix (stubs/fake-matrix)
        net {:ssid "ssid-z"}]
    (with-redefs [wireless-query/get-wireless-net-by-matrix (fn [_] net)
                  platform-be/be-get-world-safe (fn [_] :world)
                  world-registry/get-world-data (fn [_] :world-data)
                  network-command/destroy-network! (fn [_ _] true)
                  platform-be/get-capability (fn [tile cap-key]
                                               (when (and (= tile :mt)
                                                          (= cap-key WirelessCapabilityKeys/MATRIX))
                                                 matrix))
                  platform-events/fire-event! (fn [evt] (swap! events conj evt))]
      (is (true? (wireless-command/destroy-network! :mt)))
      (is (= 1 (count @events)))
      (let [e (first @events)]
        (is (= :destroyed (:action e)))
        (is (= "ssid-z" (:ssid e)))))))

(deftest is-node-linked-uses-network-lookup
  (with-redefs [wireless-query/get-wireless-net-by-node (fn [_] {:ssid "x"})]
    (is (true? (wireless-query/is-node-linked? :tile))))
  (with-redefs [wireless-query/get-wireless-net-by-node (fn [_] nil)]
    (is (false? (wireless-query/is-node-linked? :tile)))))

(deftest link-node-fires-connected-map
  (testing "link-node-to-network! posts topology map when link succeeds"
    (let [events (atom [])
          matrix (stubs/fake-matrix)
          node (stubs/fake-node "pw")]
      (with-redefs [wireless-query/get-wireless-net-by-matrix (fn [_] {:ssid "ssid-a"})
                    platform-be/be-get-world-safe (fn [_] :world)
                    world-registry/get-world-data (fn [_] :world-data)
                    vb/create-vnode (fn [_] :node-vb)
                    network-command/link-node-to-network! (fn [_ _ _ _] true)
                    platform-be/get-capability (fn [tile cap-key]
                                                 (cond
                                                   (and (= tile :matrix-tile)
                                                        (= cap-key WirelessCapabilityKeys/MATRIX)) matrix
                                                   (and (= tile :node-tile)
                                                        (= cap-key WirelessCapabilityKeys/NODE)) node
                                                   :else nil))
                    platform-events/fire-event! (fn [evt] (swap! events conj evt))]
        (is (true? (wireless-command/link-node-to-network! :node-tile :matrix-tile "pw")))
        (is (= 1 (count @events)))
        (let [e (first @events)]
          (is (= :topology/node (:kind e)))
          (is (= :connected (:action e)))
          (is (= matrix (:matrix e)))
          (is (= node (:node e))))))))

(deftest link-generator-fires-generator-linked-map
  (testing "link-generator-to-node! posts map when auth and link succeed"
    (let [events (atom [])
          node (stubs/fake-node "pw")
          gen (stubs/fake-generator)]
      (with-redefs [platform-be/be-get-world-safe (fn [_] :world)
                    world-registry/get-world-data (fn [_] :world-data)
                    vb/create-vnode-conn (fn [_] :node-conn-vb)
                    network-command/ensure-node-connection! (fn [_ _] :conn)
                    vb/create-vgenerator (fn [_] :gen-vb)
                    network-command/link-generator-to-connection! (fn [_ _ _] true)
                    platform-be/get-capability (fn [tile cap-key]
                                                 (cond
                                                   (and (= tile :node-tile)
                                                        (= cap-key WirelessCapabilityKeys/NODE)) node
                                                   (and (= tile :gen-tile)
                                                        (= cap-key WirelessCapabilityKeys/GENERATOR)) gen
                                                   :else nil))
                    platform-events/fire-event! (fn [evt] (swap! events conj evt))]
        (is (true? (wireless-command/link-generator-to-node! :gen-tile :node-tile "pw" true)))
        (is (= 1 (count @events)))
        (let [e (first @events)]
          (is (= :generator-linked (:action e)))
          (is (= node (:node e)))
          (is (= gen (:generator e))))))))

(deftest link-receiver-fires-receiver-linked-map
  (testing "link-receiver-to-node! posts map when auth and link succeed"
    (let [events (atom [])
          node (stubs/fake-node "pw")
          rec (stubs/fake-receiver)]
      (with-redefs [platform-be/be-get-world-safe (fn [_] :world)
                    world-registry/get-world-data (fn [_] :world-data)
                    vb/create-vnode-conn (fn [_] :node-conn-vb)
                    network-command/ensure-node-connection! (fn [_ _] :conn)
                    vb/create-vreceiver (fn [_] :rec-vb)
                    network-command/link-receiver-to-connection! (fn [_ _ _] true)
                    platform-be/get-capability (fn [tile cap-key]
                                                 (cond
                                                   (and (= tile :node-tile)
                                                        (= cap-key WirelessCapabilityKeys/NODE)) node
                                                   (and (= tile :rec-tile)
                                                        (= cap-key WirelessCapabilityKeys/RECEIVER)) rec
                                                   :else nil))
                    platform-events/fire-event! (fn [evt] (swap! events conj evt))]
        (is (true? (wireless-command/link-receiver-to-node! :rec-tile :node-tile "pw" true)))
        (is (= 1 (count @events)))
        (let [e (first @events)]
          (is (= :receiver-linked (:action e)))
          (is (= node (:node e)))
          (is (= rec (:receiver e))))))))

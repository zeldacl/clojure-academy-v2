(ns cn.li.ac.wireless.api-event-regression-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.wireless.api :as wapi]
            [cn.li.ac.wireless.data.world :as wd]
            [cn.li.ac.wireless.core.vblock :as vb]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.events :as platform-events])
  (:import [cn.li.acapi.wireless
            IWirelessMatrix
            IWirelessNode
            IWirelessGenerator
            IWirelessReceiver
            WirelessCapabilityKeys]
           [cn.li.acapi.wireless.event
            WirelessNetworkEvent$NetworkCreated
            WirelessNetworkEvent$NodeConnected
            WirelessNetworkEvent$GeneratorLinked
            WirelessNetworkEvent$ReceiverLinked]))

(defn- fake-matrix []
  (reify IWirelessMatrix
    (getMatrixCapacity [_] 64)
    (getMatrixBandwidth [_] 128.0)
    (getMatrixRange [_] 16.0)
    (getSsid [_] "ssid-a")
    (getPassword [_] "pw")
    (getPlacerName [_] "tester")))

(defn- fake-node [password]
  (reify IWirelessNode
    (getEnergy [_] 0.0)
    (setEnergy [_ _] nil)
    (getMaxEnergy [_] 1000.0)
    (getBandwidth [_] 100.0)
    (getCapacity [_] 8)
    (getRange [_] 10.0)
    (getNodeName [_] "node-a")
    (getPassword [_] password)
    (getBlockPos [_] nil)))

(defn- fake-generator []
  (reify IWirelessGenerator
    (getEnergy [_] 0.0)
    (setEnergy [_ _] nil)
    (getProvidedEnergy [_ _] 0.0)
    (getGeneratorBandwidth [_] 100.0)))

(defn- fake-receiver []
  (reify IWirelessReceiver
    (getRequiredEnergy [_] 0.0)
    (injectEnergy [_ _] 0.0)
    (pullEnergy [_ _] 0.0)
    (getReceiverBandwidth [_] 100.0)))

(deftest create-network-fires-network-created-event
  (testing "create-network! posts NetworkCreated when create succeeds"
    (let [events (atom [])
          matrix (fake-matrix)]
      (with-redefs [platform-be/be-get-world-safe (fn [_] :world)
                    wd/get-world-data (fn [_] :world-data)
                    vb/create-vmatrix (fn [_] :matrix-vb)
                    wd/create-network-impl! (fn [_ _ _ _] true)
                    platform-be/get-capability (fn [tile cap-key]
                                                 (when (and (= tile :matrix-tile)
                                                            (= cap-key WirelessCapabilityKeys/MATRIX))
                                                   matrix))
                    platform-events/fire-event! (fn [evt] (swap! events conj evt))]
        (is (true? (wapi/create-network! :matrix-tile "ssid-a" "pw")))
        (is (= 1 (count @events)))
        (is (instance? WirelessNetworkEvent$NetworkCreated (first @events)))
        (is (= "ssid-a" (.getSsid ^WirelessNetworkEvent$NetworkCreated (first @events))))
        (is (= matrix (.getMatrix ^WirelessNetworkEvent$NetworkCreated (first @events))))))))

(deftest link-node-fires-node-connected-event
  (testing "link-node-to-network! posts NodeConnected when link succeeds"
    (let [events (atom [])
          matrix (fake-matrix)
          node (fake-node "pw")]
      (with-redefs [wapi/get-wireless-net-by-matrix (fn [_] {:ssid "ssid-a"})
                    platform-be/be-get-world-safe (fn [_] :world)
                    wd/get-world-data (fn [_] :world-data)
                    vb/create-vnode (fn [_] :node-vb)
                    wd/link-node-to-network! (fn [_ _ _ _] true)
                    platform-be/get-capability (fn [tile cap-key]
                                                 (cond
                                                   (and (= tile :matrix-tile)
                                                        (= cap-key WirelessCapabilityKeys/MATRIX)) matrix
                                                   (and (= tile :node-tile)
                                                        (= cap-key WirelessCapabilityKeys/NODE)) node
                                                   :else nil))
                    platform-events/fire-event! (fn [evt] (swap! events conj evt))]
        (is (true? (wapi/link-node-to-network! :node-tile :matrix-tile "pw")))
        (is (= 1 (count @events)))
        (is (instance? WirelessNetworkEvent$NodeConnected (first @events)))
        (is (= node (.getNode ^WirelessNetworkEvent$NodeConnected (first @events))))))))

(deftest link-generator-fires-generator-linked-event
  (testing "link-generator-to-node! posts GeneratorLinked when auth and link succeed"
    (let [events (atom [])
          node (fake-node "pw")
          gen (fake-generator)]
      (with-redefs [platform-be/be-get-world-safe (fn [_] :world)
                    wd/get-world-data (fn [_] :world-data)
                    vb/create-vnode-conn (fn [_] :node-conn-vb)
                    wd/ensure-node-connection! (fn [_ _] :conn)
                    vb/create-vgenerator (fn [_] :gen-vb)
                    wd/link-generator-to-node-connection! (fn [_ _ _] true)
                    platform-be/get-capability (fn [tile cap-key]
                                                 (cond
                                                   (and (= tile :node-tile)
                                                        (= cap-key WirelessCapabilityKeys/NODE)) node
                                                   (and (= tile :gen-tile)
                                                        (= cap-key WirelessCapabilityKeys/GENERATOR)) gen
                                                   :else nil))
                    platform-events/fire-event! (fn [evt] (swap! events conj evt))]
        (is (true? (wapi/link-generator-to-node! :gen-tile :node-tile "pw" true)))
        (is (= 1 (count @events)))
        (is (instance? WirelessNetworkEvent$GeneratorLinked (first @events)))
        (is (= node (.getNode ^WirelessNetworkEvent$GeneratorLinked (first @events))))))))

(deftest link-receiver-fires-receiver-linked-event
  (testing "link-receiver-to-node! posts ReceiverLinked when auth and link succeed"
    (let [events (atom [])
          node (fake-node "pw")
          rec (fake-receiver)]
      (with-redefs [platform-be/be-get-world-safe (fn [_] :world)
                    wd/get-world-data (fn [_] :world-data)
                    vb/create-vnode-conn (fn [_] :node-conn-vb)
                    wd/ensure-node-connection! (fn [_ _] :conn)
                    vb/create-vreceiver (fn [_] :rec-vb)
                    wd/link-receiver-to-node-connection! (fn [_ _ _] true)
                    platform-be/get-capability (fn [tile cap-key]
                                                 (cond
                                                   (and (= tile :node-tile)
                                                        (= cap-key WirelessCapabilityKeys/NODE)) node
                                                   (and (= tile :rec-tile)
                                                        (= cap-key WirelessCapabilityKeys/RECEIVER)) rec
                                                   :else nil))
                    platform-events/fire-event! (fn [evt] (swap! events conj evt))]
        (is (true? (wapi/link-receiver-to-node! :rec-tile :node-tile "pw" true)))
        (is (= 1 (count @events)))
        (is (instance? WirelessNetworkEvent$ReceiverLinked (first @events)))
        (is (= node (.getNode ^WirelessNetworkEvent$ReceiverLinked (first @events))))))))

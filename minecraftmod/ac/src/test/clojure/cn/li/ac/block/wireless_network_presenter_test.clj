(ns cn.li.ac.block.wireless-network-presenter-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.block.wireless-matrix.network-presenter :as matrix-presenter]
            [cn.li.ac.block.wireless-node.network-presenter :as node-presenter]
            [cn.li.ac.test.support.wireless-stubs :as stubs]
            [cn.li.ac.wireless.core.vblock :as vb]
            [cn.li.ac.wireless.data.network-state :as network-state]))

(defn- network
  [ssid password]
  (network-state/create-wireless-net
    {:world :w :net-lookup (atom {}) :spatial-index (atom {})}
    (vb/create-vmatrix 0 0 0)
    ssid
    password))

(deftest node-presenter-uses-plain-network-values-test
  (let [linked (network "linked" "secret")
        avail (network "other" "")]
    (is (= {:ssid "linked" :is-encrypted? true}
           (node-presenter/linked->dto linked)))
    (is (= {:linked {:ssid "linked" :is-encrypted? true}
            :avail [{:ssid "other"
                     :is-encrypted? false
                     :load 0
                     :capacity 64
                     :bandwidth 128.0
                     :range 16.0}]}
           (node-presenter/list-networks-response
             {:linked linked
              :linked-ssid "linked"
              :avail [linked avail]
              :matrix-cap-fn (constantly (stubs/fake-matrix))
              :matrix-capacity #(.getMatrixCapacity %)
              :matrix-bandwidth #(.getMatrixBandwidth %)
              :matrix-range #(.getMatrixRange %)})))))

(deftest matrix-presenter-uses-plain-network-values-test
  (let [network (network "ssid" "pw")]
    (network-state/update-nodes! network conj :node-a)
    (is (= {:ssid "ssid"
            :password "pw"
            :owner "tester"
            :load 1
            :max-capacity 64
            :range 16.0
            :bandwidth 128.0
            :initialized true}
           (matrix-presenter/gather-info-response network (stubs/fake-matrix))))))
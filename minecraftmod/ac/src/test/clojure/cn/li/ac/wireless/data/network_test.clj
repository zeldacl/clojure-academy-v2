(ns cn.li.ac.wireless.data.network-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.test.support.wireless-stubs :as stubs]
            [cn.li.ac.wireless.core.vblock :as vb]
            [cn.li.ac.wireless.data.network-state :as network-state]
            [cn.li.ac.wireless.data.network-membership :as network-membership]
            [cn.li.ac.wireless.data.network-validation :as network-validation]
            [cn.li.ac.wireless.data.network-runtime :as network-runtime]
            [cn.li.ac.wireless.data.network-serialization :as network-ser]
            [cn.li.ac.wireless.data.network-config :as ncfg]
            [cn.li.ac.wireless.data.world :as wdata]))

(deftest create-and-basic-accessors-test
  (let [wd {:world :w :net-lookup (atom {}) :spatial-index (atom {})}
        matrix {:x 0 :y 0 :z 0}
        n (network-state/create-wireless-net wd matrix "ssid" "pw")]
    (is (= "ssid" (network-state/get-ssid n)))
    (is (= "pw" (network-state/get-password n)))
    (is (= 0 (network-state/get-load n)))
    (is (false? (network-state/is-disposed? n)))))

(deftest add-node-password-and-capacity-and-range-gates-test
  (let [w :w-net
        wd (wdata/create-world-data w)
        tiles (atom {[0 0 0] (stubs/fake-matrix {:capacity 1 :matrix-range 16.0})})
        matrix-vb (vb/create-vmatrix 0 0 0)
        near-node (vb/create-vnode 5 0 0)
        far-node (vb/create-vnode 100 0 0)]
    (stubs/with-tile-world tiles
      (fn []
        (is (true? (wdata/create-network-impl! wd matrix-vb "myssid" "secret")))
        (let [net (wdata/get-network-by-ssid wd "myssid")]
          (is (false? (network-membership/add-node! net near-node "wrong")))
          (is (true? (network-membership/add-node! net near-node "secret")))
          (is (false? (network-membership/add-node! net (vb/create-vnode 6 0 0) "secret")))
          (is (false? (network-membership/add-node! net far-node "secret")))
          (is (= net (get @(:net-lookup wd) near-node))))))))

(deftest remove-node-cleanup-on-tick-test
  (let [w :w-net2
        wd (wdata/create-world-data w)
        tiles (atom {[0 0 0] (stubs/fake-matrix {:capacity 4})
                     [3 0 0] (stubs/mutable-node {})})
        matrix-vb (vb/create-vmatrix 0 0 0)
        node-vb (vb/create-vnode 3 0 0)]
    (stubs/with-tile-world tiles
      (fn []
        (is (true? (wdata/create-network-impl! wd matrix-vb "n2" "p")))
        (let [net (wdata/get-network-by-ssid wd "n2")]
          (is (true? (network-membership/add-node! net node-vb "p")))
          (is (some? (get @(:net-lookup wd) node-vb)))
          (network-membership/remove-node! net node-vb)
          (with-redefs [ncfg/update-interval-ticks (constantly 1)]
            (network-runtime/tick-wireless-net! net))
          (is (nil? (get @(:net-lookup wd) node-vb))))))))

(deftest validate-disposes-when-matrix-destroyed-test
  (let [w :w-net3
        wd (wdata/create-world-data w)
        tiles (atom {[0 0 0] (stubs/fake-matrix {})})
        matrix-vb (vb/create-vmatrix 0 0 0)]
    (stubs/with-tile-world tiles
      (fn []
        (is (true? (wdata/create-network-impl! wd matrix-vb "n3" "p")))
        (let [net (wdata/get-network-by-ssid wd "n3")]
          (is (true? (network-validation/validate! net)))
          (swap! tiles dissoc [0 0 0])
          (is (false? (network-validation/validate! net)))
          (is (true? (network-state/is-disposed? net))))))))

(deftest is-in-range-without-matrix-test
  (let [wd (wdata/create-world-data :w4)
        tiles (atom {})
        net (network-state/create-wireless-net wd (vb/create-vmatrix 0 0 0) "s" "p")]
    (stubs/with-tile-world tiles
      (fn []
        (is (false? (network-validation/is-in-range? net 0 0 0)))))))

(deftest network-nbt-round-trip-test
  (let [w :w-nbt
        wd (wdata/create-world-data w)
        m (stubs/fake-matrix {})
        n (stubs/mutable-node {})
        tiles (atom {[0 0 0] m [7 0 0] n})
        matrix-vb (vb/create-vmatrix 0 0 0)
        node-vb (vb/create-vnode 7 0 0)]
    (stubs/with-tile-world tiles
      (fn []
        (let [net (network-state/create-wireless-net wd matrix-vb "ssid-nbt" "pw99")]
          (reset! (:buffer net) 12.5)
          (is (true? (network-membership/add-node! net node-vb "pw99")))
          (let [comp (network-ser/network-to-nbt net)
                net2 (network-ser/network-from-nbt wd comp)]
            (is (= "ssid-nbt" (network-state/get-ssid net2)))
            (is (= "pw99" (network-state/get-password net2)))
            (is (= 12.5 @(:buffer net2)))
            (is (= 1 (count @(:nodes net2))))))))))

(deftest balance-energy-moves-toward-average-test
  (let [w :w-bal
        wd (wdata/create-world-data w)
        n1 (stubs/mutable-node {:energy 900.0 :max-energy 1000.0})
        n2 (stubs/mutable-node {:energy 100.0 :max-energy 1000.0})
        tiles (atom {[0 0 0] (stubs/fake-matrix {:bandwidth 5000.0})
                     [2 0 0] n1
                     [4 0 0] n2})
        matrix-vb (vb/create-vmatrix 0 0 0)
        vb1 (vb/create-vnode 2 0 0)
        vb2 (vb/create-vnode 4 0 0)]
    (stubs/with-tile-world tiles
      (fn []
        (let [net (network-state/create-wireless-net wd matrix-vb "bal" "p")]
          (is (true? (network-membership/add-node! net vb1 "p")))
          (is (true? (network-membership/add-node! net vb2 "p")))
          (with-redefs [ncfg/update-interval-ticks (constantly 1)
                        ncfg/buffer-max (constantly 1.0e6)]
            (dotimes [_ 3]
              (network-runtime/tick-wireless-net! net)))
          (let [e1 (.getEnergy n1)
                e2 (.getEnergy n2)]
            (is (< (Math/abs (- e1 e2)) 120.0)
                "energies should move closer after balancing")))))))

(ns cn.li.ac.wireless.data.world-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.test.support.wireless-stubs :as stubs]
            [cn.li.ac.wireless.core.vblock :as vb]
            [cn.li.ac.wireless.data.network :as wireless-net]
            [cn.li.ac.wireless.data.world :as world]))

(deftest get-world-data-caches-per-world-test
  (let [w :world-a
        a (world/get-world-data w)
        b (world/get-world-data w)]
    (is (identical? a b))
    (is (= w (:world a)))
    (world/remove-world-data! w)))

(deftest create-network-uniqueness-test
  (let [wd (world/create-world-data :w)
        matrix {:x 0 :y 0 :z 0}]
    (with-redefs [wireless-net/create-wireless-net
                  (fn [world-data matrix-vblock ssid password]
                    {:world-data world-data
                     :matrix matrix-vblock
                     :ssid (atom ssid)
                     :password (atom password)
                     :nodes (atom [])
                     :disposed (atom false)})]
      (is (true? (world/create-network-impl! wd matrix "s1" "pw")))
      (is (false? (world/create-network-impl! wd matrix "s1" "pw2"))))))

(deftest create-network-registers-lookups-test
  (let [w :w-lu
        wd (world/create-world-data w)
        tiles (atom {[0 0 0] (stubs/fake-matrix {})})
        matrix-vb (vb/create-vmatrix 0 0 0)]
    (stubs/with-tile-world tiles
      (fn []
        (is (true? (world/create-network-impl! wd matrix-vb "reg" "pw")))
        (is (some? (world/get-network-by-matrix wd matrix-vb)))
        (is (some? (world/get-network-by-ssid wd "reg")))
        (is (pos? (count @(:spatial-index wd))))))))

(deftest destroy-network-clears-node-lookups-test
  (let [w :w-dest
        wd (world/create-world-data w)
        m (stubs/fake-matrix {:capacity 4})
        n (stubs/mutable-node {})
        tiles (atom {[0 0 0] m [3 0 0] n})
        matrix-vb (vb/create-vmatrix 0 0 0)
        node-vb (vb/create-vnode 3 0 0)]
    (stubs/with-tile-world tiles
      (fn []
        (is (true? (world/create-network-impl! wd matrix-vb "dnet" "p")))
        (let [net (world/get-network-by-ssid wd "dnet")]
          (is (true? (wireless-net/add-node! net node-vb "p")))
          (is (some? (get @(:net-lookup wd) node-vb)))
          (world/destroy-network-impl! wd net)
          (is (nil? (get @(:net-lookup wd) node-vb)))
          (is (nil? (world/get-network-by-ssid wd "dnet"))))))))

(ns cn.li.ac.terminal.freq-transmitter-handlers-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.terminal.apps.freq-transmitter-handlers :as h]
            [cn.li.ac.test.support.wireless-stubs :as stubs]
            [cn.li.mcmod.platform.position :as pos]
            [cn.li.mcmod.platform.world :as pworld]))

(defn- with-level
  [tiles f]
  (binding [pos/*position-factory* (fn [x y z] (stubs/->TestPos x y z))
            pworld/*world-get-tile-entity-fn*
            (fn [_ p]
              (get tiles [(pos/position-get-x p) (pos/position-get-y p) (pos/position-get-z p)]))]
    (f)))

(deftest query-and-auth-matrix-test
  (let [matrix (stubs/fake-matrix {:capacity 4})
        level :world
        tiles {[0 0 0] matrix}]
    (with-level tiles
      (fn []
        (is (= "ssid-a" (h/query-ssid level {:x 0 :y 0 :z 0})))
        (is (true? (h/auth-matrix level {:x 0 :y 0 :z 0} "pw")))
        (is (false? (h/auth-matrix level {:x 0 :y 0 :z 0} "wrong")))))))

(deftest auth-node-test
  (let [node (stubs/fake-node "node-pw")
        level :world
        tiles {[1 0 0] node}]
    (with-level tiles
      (fn []
        (is (true? (h/auth-node level {:x 1 :y 0 :z 0} "node-pw")))
        (is (false? (h/auth-node level {:x 1 :y 0 :z 0} "nope")))))))

(deftest link-node-and-link-user-test
  (let [matrix (stubs/fake-matrix {})
        node (stubs/mutable-node {})
        gen (stubs/generator-stub {})
        rec (stubs/receiver-stub {})
        level :world
        tiles {[0 0 0] matrix
               [1 0 0] node
               [2 0 0] gen
               [3 0 0] rec}]
    (with-level tiles
      (fn []
        (with-redefs [cn.li.ac.wireless.api/link-node-to-network! (fn [_node _matrix pwd] (= "pw" pwd))
                      cn.li.ac.wireless.api/link-generator-to-node! (fn [_u _n _ _] true)
                      cn.li.ac.wireless.api/link-receiver-to-node! (fn [_u _n _ _] true)]
          (is (true? (h/link-node level {:x 1 :y 0 :z 0} {:x 0 :y 0 :z 0} "pw")))
          (is (false? (h/link-node level {:x 1 :y 0 :z 0} {:x 0 :y 0 :z 0} "bad")))
          (is (true? (h/link-user level {:x 2 :y 0 :z 0} {:x 1 :y 0 :z 0})))
          (is (true? (h/link-user level {:x 3 :y 0 :z 0} {:x 1 :y 0 :z 0}))))))))


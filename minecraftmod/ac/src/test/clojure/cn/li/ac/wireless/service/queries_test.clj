(ns cn.li.ac.wireless.service.queries-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.test.support.wireless-stubs :as stubs]
            [cn.li.ac.wireless.config :as wireless-config]
            [cn.li.ac.wireless.core.capability-resolver :as resolver]
            [cn.li.ac.wireless.core.vblock :as vb]
            [cn.li.ac.wireless.data.network-lookup :as lookup]
            [cn.li.ac.wireless.data.node-conn :as node-conn]
            [cn.li.ac.wireless.data.spatial-lookup :as spatial]
            [cn.li.ac.wireless.service.queries :as query]
            [cn.li.ac.wireless.data.world-registry :as world-registry]))

(deftest find-available-nodes-at-filters-range-and-capacity-test
  (let [world :world
        world-data :world-data
        available-vb {:id :available}
        full-vb {:id :full}
        far-vb {:id :far}
        available-node (stubs/mutable-node {:range 10.0})
        full-node (stubs/mutable-node {:range 10.0})
        far-node (stubs/mutable-node {:range 100.0})
        full-conn {:kind :full-conn}]
    (with-redefs [wireless-config/node-search-range (fn [] 10)
                  wireless-config/max-results (fn [] 10)
                  world-registry/get-world-data (fn [w]
                                              (is (= world w))
                                              world-data)
                  spatial/get-nearby-chunks (fn [x y z r]
                                              (is (= [0 0 0 10] [x y z r]))
                                              [:chunk])
                  spatial/get-vblocks-in-chunks (fn [wd chunks]
                                                  (is (= world-data wd))
                                                  (is (= [:chunk] chunks))
                                                  [available-vb full-vb far-vb])
                  vb/dist-sq-pos (fn [node-vb _x _y _z]
                                   ({available-vb 9
                                     full-vb 4
                                     far-vb 121}
                                    node-vb))
                  resolver/resolve-node-cap (fn [_world node-vb]
                                              ({available-vb available-node
                                                full-vb full-node
                                                far-vb far-node}
                                               node-vb))
                  lookup/get-node-connection (fn [_wd node-vb]
                                               ({full-vb full-conn}
                                                node-vb))
                  node-conn/get-receivers (fn [conn]
                                            (is (= full-conn conn))
                                            [:r])
                  node-conn/get-generators (fn [conn]
                                             (is (= full-conn conn))
                                             [])
                  node-conn/get-capacity (fn [conn]
                                           (is (= full-conn conn))
                                           1)]
      (testing "available nodes are within search range, within node range, and not at capacity"
        (is (= [available-node]
               (vec (query/find-available-nodes-at world 0 0 0))))))))

(deftest find-available-nodes-at-respects-max-results-test
  (let [world :world
        node-a-vb {:id :a}
        node-b-vb {:id :b}
        node-a (stubs/mutable-node {:range 10.0})
        node-b (stubs/mutable-node {:range 10.0})]
    (with-redefs [wireless-config/node-search-range (fn [] 10)
                  wireless-config/max-results (fn [] 1)
                  world-registry/get-world-data (constantly :world-data)
                  spatial/get-nearby-chunks (constantly [:chunk])
                  spatial/get-vblocks-in-chunks (constantly [node-a-vb node-b-vb])
                  vb/dist-sq-pos (constantly 1)
                  resolver/resolve-node-cap (fn [_ node-vb]
                                              ({node-a-vb node-a
                                                node-b-vb node-b}
                                               node-vb))
                  lookup/get-node-connection (constantly nil)]
      (is (= [node-a]
             (vec (query/find-available-nodes-at world 0 0 0)))))))

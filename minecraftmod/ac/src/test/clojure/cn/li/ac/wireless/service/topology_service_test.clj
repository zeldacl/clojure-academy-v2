(ns cn.li.ac.wireless.service.topology-service-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.wireless.core.vblock :as vb]
            [cn.li.ac.wireless.data.network-state :as network-state]
            [cn.li.ac.wireless.data.topology-index :as topology-index]
            [cn.li.ac.wireless.data.world :as world]
            [cn.li.ac.wireless.service.topology-service :as topology-service]))

(defn- test-world
  [world-id]
  {:server-session-id :test-session
   :world-id world-id})

(deftest create-network-registers-through-topology-service-test
  (let [wd (world/create-world-data (test-world :topology-service-world))
        matrix-vb (vb/create-vmatrix 0 0 0)]
    (is (true? (topology-service/create-network! wd matrix-vb "svc" "pw")))
    (is (false? (topology-service/create-network! wd matrix-vb "svc" "pw2")))
    (let [network (topology-index/get-network-by-ssid wd "svc")]
      (is (some? network))
      (is (identical? network (topology-index/get-network-by-matrix wd matrix-vb))))))

(deftest change-network-ssid-updates-index-test
  (let [wd (world/create-world-data (test-world :topology-service-rename))
        matrix-vb (vb/create-vmatrix 0 0 0)]
    (is (true? (topology-service/create-network! wd matrix-vb "old" "pw")))
    (let [network (topology-index/get-network-by-ssid wd "old")]
      (is (true? (topology-service/change-network-ssid! network "new")))
      (is (= "new" (network-state/get-ssid network)))
      (is (nil? (topology-index/get-network-by-ssid wd "old")))
      (is (identical? network (topology-index/get-network-by-ssid wd "new"))))))

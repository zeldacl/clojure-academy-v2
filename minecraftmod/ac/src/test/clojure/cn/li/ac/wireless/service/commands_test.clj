(ns cn.li.ac.wireless.service.commands-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.wireless.core.vblock :as vb]
            [cn.li.ac.wireless.data.network-lookup :as lookup]
            [cn.li.ac.wireless.data.network-state :as network-state]
            [cn.li.ac.wireless.data.world :as world]
            [cn.li.ac.wireless.service.commands :as commands]))

(defn- test-world
  [world-id]
  {:server-session-id :test-session
   :world-id world-id})

(deftest create-network-registers-indexes-test
  (let [wd (world/create-world-data (test-world :commands-world))
        matrix-vb (vb/create-vmatrix 0 0 0)]
    (is (true? (commands/create-network! wd matrix-vb "svc" "pw")))
    (is (false? (commands/create-network! wd matrix-vb "svc" "pw2")))
    (let [network (lookup/get-network-by-ssid wd "svc")]
      (is (some? network))
      (is (identical? network (lookup/get-network-by-matrix wd matrix-vb))))))

(deftest change-network-ssid-updates-index-test
  (let [wd (world/create-world-data (test-world :commands-rename))
        matrix-vb (vb/create-vmatrix 0 0 0)]
    (is (true? (commands/create-network! wd matrix-vb "old" "pw")))
    (let [network (lookup/get-network-by-ssid wd "old")]
      (is (true? (commands/change-network-ssid! network "new")))
      (let [renamed (lookup/get-network-by-ssid wd "new")]
        (is (= "new" (network-state/get-ssid renamed)))
        (is (nil? (lookup/get-network-by-ssid wd "old")))
        (is (some? renamed))))))

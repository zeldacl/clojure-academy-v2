(ns cn.li.ac.block.wireless-node-handlers-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.block.wireless-node.handlers :as handlers]
            [cn.li.ac.block.wireless-node.owner :as node-owner]
            [cn.li.ac.wireless.gui.sync.handler :as sync-handler]
            [cn.li.mcmod.platform.be :as platform-be]))

(deftest owner-authorization-guards-node-mutations-test
  (testing "non-owner requests are rejected before generated mutation handler runs"
    (with-redefs [sync-handler/get-world (fn [_] :world)
                  sync-handler/get-tile-at (fn [_ _] :tile)
                  platform-be/get-custom-state (fn [_] {:placer-name "owner"})
                  handlers/generated-network-handlers {:change-name (fn [_ _] (throw (ex-info "should-not-run" {})))}]
      (is (= {:success false}
             (handlers/handle-change-name {:node-name "new"} :player)))))

  (testing "owner requests run generated mutation handler"
    (let [called (atom nil)]
      (with-redefs [sync-handler/get-world (fn [_] :world)
                    sync-handler/get-tile-at (fn [_ _] :tile)
                    platform-be/get-custom-state (fn [_] {:placer-name "owner"})
                    handlers/generated-network-handlers {:change-name (fn [payload player]
                                                                        (reset! called [payload player])
                                                                        {:success true})}
                    ;; owner helper compatibility: compare against this normalized name
                    node-owner/player-name (fn [_] "owner")]
        (is (= {:success true}
               (handlers/handle-change-name {:node-name "new-name"} :player)))
        (is (= [{:node-name "new-name"} :player] @called))))))

(deftest change-password-owner-guard-test
  (testing "blank owner keeps compatibility and allows mutation"
    (with-redefs [sync-handler/get-world (fn [_] :world)
                  sync-handler/get-tile-at (fn [_ _] :tile)
                  platform-be/get-custom-state (fn [_] {:placer-name ""})
                  handlers/generated-network-handlers {:change-password (fn [_ _] {:success true})}]
      (is (= {:success true}
             (handlers/handle-change-password {:password "x"} :player)))))

  (testing "missing tile is rejected"
    (with-redefs [sync-handler/get-world (fn [_] :world)
                  sync-handler/get-tile-at (fn [_ _] nil)
                  handlers/generated-network-handlers {:change-password (fn [_ _] {:success true})}]
      (is (= {:success false}
             (handlers/handle-change-password {:password "x"} :player))))))

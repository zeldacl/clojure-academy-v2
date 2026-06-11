(ns cn.li.ac.block.wireless-node-handlers-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.block.wireless-node.handlers :as handlers]
            [cn.li.ac.block.wireless-node.logic :as node-logic]
            [cn.li.ac.wireless.gui.sync.handler :as sync-handler]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.ac.wireless.feedback :as feedback]))

(deftest owner-authorization-guards-node-mutations-test
  (testing "non-owner requests are rejected"
    (with-redefs [sync-handler/get-world (fn [_] :world)
                  sync-handler/get-tile-at (fn [_ _] :tile)
                  platform-be/get-custom-state (fn [_] {:placer-name "owner"})
                  node-logic/player-name (fn [_] "not-owner")]
      (let [result (handlers/handle-change-name {:node-name "new"} :player)]
        (is (false? (:success result)))
        (is (seq (:messages result))))))

  (testing "owner requests run mutation handler"
    (with-redefs [sync-handler/get-world (fn [_] :world)
                  sync-handler/get-tile-at (fn [_ _] :tile)
                  platform-be/get-custom-state (fn [_] {:placer-name "owner"})
                  node-logic/player-name (fn [_] "owner")]
      (let [result (handlers/handle-change-name {:node-name "new-name"} :player)]
        ;; Authorized + tile exists + valid new-name => success
        (is (:success result))
        (is (seq (:messages result)))))))

(deftest change-password-owner-guard-test
  (testing "blank owner allows mutation"
    (with-redefs [sync-handler/get-world (fn [_] :world)
                  sync-handler/get-tile-at (fn [_ _] :tile)
                  platform-be/get-custom-state (fn [_] {:placer-name ""})
                  node-logic/player-name (fn [_] "anyone")]
      (let [result (handlers/handle-change-password {:password "x"} :player)]
        (is (:success result))
        (is (seq (:messages result))))))

  (testing "missing tile is rejected"
    (with-redefs [sync-handler/get-world (fn [_] :world)
                  sync-handler/get-tile-at (fn [_ _] nil)
                  node-logic/player-name (fn [_] "owner")]
      (let [result (handlers/handle-change-password {:password "x"} :player)]
        (is (false? (:success result)))
        (is (seq (:messages result)))))))

(ns cn.li.ac.block.wireless-matrix-owner-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.block.wireless-matrix.logic :as logic]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.world :as world]))

(deftest owner-authorized-check
  (with-redefs [entity/player-get-name (fn [_] "alice")]
    (testing "exact canonical owner"
      (is (true? (logic/owner-authorized? "alice" :player))))

    (testing "legacy owner string"
      (is (true? (logic/owner-authorized?
                  "ServerPlayer['alice'/123, l='ServerLevel[test]', x=0.0, y=0.0, z=0.0]"
                  :player))))

    (testing "blank owner remains permissive"
      (is (true? (logic/owner-authorized? "" :player))))

    (testing "whitespace-only owner remains permissive"
      (is (true? (logic/owner-authorized? "   " :player))))

    (testing "different owner is rejected"
      (is (false? (logic/owner-authorized? "bob" :player))))

    (testing "owner comparison is case-sensitive"
      (is (false? (logic/owner-authorized? "Alice" :player))))))

(deftest placer-name-from-state
  (is (= "Dev" (logic/placer-name {:placer-name "Dev"})))
  (is (= "" (logic/placer-name {})))
  (is (= "" (logic/placer-name nil))))

(deftest handle-matrix-place-persists-canonical-player-name
  (let [saved-state (atom nil)
        handler (logic/handle-matrix-place)]
    (with-redefs [world/world-get-tile-entity* (fn [_ _] :be)
                  platform-be/get-custom-state (fn [_] {:foo 1})
                  platform-be/set-custom-state! (fn [_ state] (reset! saved-state state))
                  entity/player-get-name (fn [_] "alice")]
      (handler {:player :player :world :world :pos :pos})
      (is (= "alice" (:placer-name @saved-state))))))

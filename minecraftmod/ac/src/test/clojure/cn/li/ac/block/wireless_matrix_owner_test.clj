(ns cn.li.ac.block.wireless-matrix-owner-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.block.wireless-matrix.network-infra :as infra]
            [cn.li.ac.block.wireless-matrix.logic :as logic]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.world :as world])
  (:import [cn.li.acapi.wireless IWirelessMatrix]))

(defn- matrix-cap-with-owner [owner]
  (reify IWirelessMatrix
    (getMatrixCapacity [_] 4)
    (getMatrixBandwidth [_] 128.0)
    (getMatrixRange [_] 16.0)
    (getSsid [_] "")
    (getPassword [_] "")
    (getPlacerName [_] owner)))

(deftest owner-check-accepts-canonical-and-legacy-owner-format
  (with-redefs [entity/player-get-name (fn [_] "alice")]
    (testing "exact canonical owner"
      (is (true? (infra/owner? (matrix-cap-with-owner "alice") :player))))

    (testing "legacy owner string"
      (is (true? (infra/owner?
                  (matrix-cap-with-owner "ServerPlayer['alice'/123, l='ServerLevel[test]', x=0.0, y=0.0, z=0.0]")
                  :player))))

    (testing "blank owner remains permissive"
      (is (true? (infra/owner? (matrix-cap-with-owner "") :player))))

    (testing "whitespace-only owner remains permissive"
      (is (true? (infra/owner? (matrix-cap-with-owner "   ") :player))))

    (testing "different owner is rejected"
      (is (false? (infra/owner? (matrix-cap-with-owner "bob") :player))))

    (testing "owner comparison is case-sensitive"
      (is (false? (infra/owner? (matrix-cap-with-owner "Alice") :player))))))

(deftest handle-matrix-place-persists-canonical-player-name
  (let [saved-state (atom nil)
        handler (logic/handle-matrix-place)]
    (with-redefs [world/world-get-tile-entity* (fn [_ _] :be)
                  platform-be/get-custom-state (fn [_] {:foo 1})
                  platform-be/set-custom-state! (fn [_ state] (reset! saved-state state))
                  entity/player-get-name (fn [_] "alice")]
      (handler {:player :player :world :world :pos :pos})
      (is (= "alice" (:placer-name @saved-state))))))

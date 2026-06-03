(ns cn.li.mcmod.platform.world-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.mcmod.platform.world :as world]))

(deftest world-op-nil-result-does-not-fallback-to-protocol-test
  (testing "when world op exists and returns nil, wrapper must not dispatch protocol"
    (world/call-with-world-ops
      {:world-get-tile-entity (fn [_ _] nil)}
      (fn []
        (is (nil? (world/world-get-tile-entity* (Object.) :pos)))))))

(deftest world-op-false-result-does-not-fallback-to-protocol-test
  (testing "when world op exists and returns false, wrapper must not dispatch protocol"
    (world/call-with-world-ops
      {:world-is-raining (fn [_] false)}
      (fn []
        (is (false? (world/world-is-raining* (Object.))))))))

(deftest missing-world-op-falls-back-to-protocol-test
  (testing "wrapper still falls back to protocol when op is not installed"
    (let [w (reify world/IWorldAccess
              (world-get-tile-entity [_ _] :from-protocol)
              (world-get-block-state [_ _] :state)
              (world-set-block [_ _ _ _] true)
              (world-remove-block [_ _] true)
              (world-break-block [_ _ _] true)
              (world-place-block-by-id [_ _ _ _] true)
              (world-is-chunk-loaded? [_ _ _] true)
              (world-get-day-time [_] 0)
              (world-get-dimension-id [_] "dim")
              (world-get-players [_] [])
              (world-is-raining [_] false)
              (world-is-client-side [_] false)
              (world-can-see-sky [_ _] true))]
      (is (= :from-protocol (world/world-get-tile-entity* w :pos))))))

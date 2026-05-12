(ns cn.li.mcmod.events.dispatcher-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.mcmod.block.query :as bquery]
            [cn.li.mcmod.events.dispatcher :as dispatcher]
            [cn.li.mcmod.block.multiblock-core :as core]))

(deftest right-click-routing-test
  (let [called (atom nil)
        ctx {:block-id :part}]
    (with-redefs [core/route-to-controller-context (fn [_] {:block-id :controller :x 1})
                  bquery/get-block-spec (fn [block-id]
                                          (when (= block-id :controller)
                                            {:events {:on-right-click (fn [routed] (reset! called routed) :ok)}}))]
      (is (= :ok (dispatcher/on-block-right-click ctx)))
      (is (= {:block-id :controller :x 1} @called))))
  (testing "no handler returns nil"
    (with-redefs [core/route-to-controller-context (fn [x] x)
                  bquery/get-block-spec (fn [_] nil)]
      (is (= nil (dispatcher/on-block-right-click {:block-id :none}))))))

(deftest on-place-priority-test
  (testing "precheck short-circuits all downstream processing"
    (let [ctx {:block-id :a}]
      (with-redefs [core/precheck-controller-place (fn [_] {:prechecked true})
                    bquery/get-block-spec (fn [_] {:events {:on-place (fn [_] {:handler true})}})
                    core/post-place-controller! (fn [_] {:core true})]
        (is (= {:prechecked true} (dispatcher/on-block-place ctx))))))
  (testing "core result has higher priority than handler result"
    (let [ctx {:block-id :a}]
      (with-redefs [core/precheck-controller-place (fn [_] nil)
                    bquery/get-block-spec (fn [_] {:events {:on-place (fn [_] {:handler true})}})
                    core/post-place-controller! (fn [_] {:core true})]
        (is (= {:core true} (dispatcher/on-block-place ctx)))))
    (with-redefs [core/precheck-controller-place (fn [_] nil)
                  bquery/get-block-spec (fn [_] {:events {:on-place (fn [_] {:handler true})}})
                  core/post-place-controller! (fn [_] nil)]
      (is (= {:handler true} (dispatcher/on-block-place {:block-id :a}))))
    (with-redefs [core/precheck-controller-place (fn [_] nil)
                  bquery/get-block-spec (fn [_] nil)
                  core/post-place-controller! (fn [_] nil)]
      (is (= nil (dispatcher/on-block-place {:block-id :a}))))))

(deftest on-break-merge-test
  (let [ctx {:block-id :part}
        routed {:block-id :controller :meta 1}]
    (with-redefs [core/route-to-controller-context (fn [_] routed)
                  bquery/get-block-spec (fn [_] {:events {:on-break (fn [_] {:a 1 :b 2})}})
                  core/apply-structure-break! (fn [_ _] {:b 9 :c 3})]
      (is (= {:a 1 :b 9 :c 3}
             (dispatcher/on-block-break ctx))))
    (with-redefs [core/route-to-controller-context (fn [_] routed)
                  bquery/get-block-spec (fn [_] nil)
                  core/apply-structure-break! (fn [_ _] nil)]
      (is (= {} (dispatcher/on-block-break ctx))))))

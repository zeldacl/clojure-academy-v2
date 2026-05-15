(ns cn.li.mcmod.block.tile-dsl-logic-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cn.li.mcmod.block.tile-dsl :as tdsl]
            [cn.li.mcmod.block.tile-logic :as tlog]
            [cn.li.mcmod.protocol.core :as registry-core]))

(defn- reset-tile-dsl! []
  (registry-core/reset-state! tdsl/tile-registry {:by-id {} :block->tile-id {}}))

(defn- reset-tile-logic! []
  (registry-core/reset-state! tlog/tile-logic-registry {})
  (registry-core/reset-state! tlog/tile-kind-registry {})
  (registry-core/reset-state! tlog/capability-registry {})
  (registry-core/reset-state! tlog/container-registry {}))

(defn- reset-all! [f]
  (reset-tile-dsl!)
  (reset-tile-logic!)
  (f)
  (reset-tile-dsl!)
  (reset-tile-logic!))

(use-fixtures :each reset-all!)

(deftest validate-tile-spec-errors-test
  (is (thrown-with-msg? Exception #"non-empty string :id"
                        (tdsl/validate-tile-spec {:id "" :impl :x :blocks ["a"]})))
  (is (thrown-with-msg? Exception #":impl must be a keyword"
                        (tdsl/validate-tile-spec {:id "t" :impl "bad" :blocks ["a"]})))
  (is (thrown-with-msg? Exception #":blocks must be a non-empty vector"
                        (tdsl/validate-tile-spec {:id "t" :impl :scripted :blocks []}))))

(deftest create-tile-spec-and-registry-test
  (let [spec (tdsl/create-tile-spec "my-tile" {:impl :scripted :blocks [:a "b"]})]
    (is (= "my-tile" (:id spec)))
    (is (= ["a" "b"] (:blocks spec)))
    (is (= :scripted (:impl spec)))
    (tdsl/register-tile! spec)
    (is (= spec (tdsl/get-tile "my-tile")))
    (is (= "my-tile" (tdsl/get-tile-id-for-block "a")))
    (is (= #{"my-tile"} (set (tdsl/list-tiles))))))

(deftest register-tile-logic-with-kind-merge-test
  (tlog/register-tile-kind! :k {:tick-fn (fn [_ _ _ _] :kind-tick)
                                :read-nbt-fn (fn [_] {:from :kind})
                                :write-nbt-fn (fn [_ _] nil)})
  (tlog/register-tile-logic! "block-a" {:tile-kind :k :tick-fn (fn [_ _ _ _] :override)})
  (is (= :override (tlog/invoke-tick "block-a" nil nil nil nil)))
  (tlog/register-tile-logic! "block-b" {:tile-kind :k})
  (is (= :kind-tick (tlog/invoke-tick "block-b" nil nil nil nil)))
  (is (= {:from :kind} (tlog/read-nbt "block-b" :tag))))

(deftest register-tile-logic-no-hooks-skipped-test
  (is (nil? (tlog/register-tile-logic! "lonely" {:tile-kind nil}))))

(deftest tile-nbt-and-container-dispatch-test
  (tlog/register-tile-logic! "nbt-b" {:read-nbt-fn (fn [t] {:tag t})
                                     :write-nbt-fn (fn [_be _t] :written)})
  (is (= {:tag :x} (tlog/read-nbt "nbt-b" :x)))
  (tlog/register-container! "c-tile" {:get-size (fn [_] 3)
                                        :get-item (fn [_ slot] {:slot slot})})
  (is (= 3 (tlog/container-size "c-tile" :be)))
  (is (= {:slot 1} (tlog/container-get-item "c-tile" :be 1)))
  (is (true? (tlog/container-still-valid "c-tile" :be :player))))

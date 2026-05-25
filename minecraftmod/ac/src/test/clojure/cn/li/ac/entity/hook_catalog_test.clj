(ns cn.li.ac.entity.hook-catalog-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.entity.hook-catalog :as hook-catalog]
            [cn.li.mcmod.entity.hook-resolver :as hook-resolver]))

(defn- clean-resolvers-fixture
  [f]
  (hook-resolver/clear-resolvers!)
  (f)
  (hook-resolver/clear-resolvers!))

(use-fixtures :each clean-resolvers-fixture)

(deftest normalize-hook-id-test
  (is (= "railgun-fx" (hook-catalog/normalize-hook-id :railgun-fx)))
  (is (= "railgun-fx" (hook-catalog/normalize-hook-id "railgun-fx")))
  (is (nil? (hook-catalog/normalize-hook-id "")))
  (is (nil? (hook-catalog/normalize-hook-id nil))))

(deftest hook-id-to-impl-key-test
  (is (= :owner-offset (hook-catalog/effect-impl-key :diamond-shield)))
  (is (= :coin-throwing (hook-catalog/effect-impl-key "coin-throwing")))
  (is (= :owner-follow (hook-catalog/ray-impl-key :railgun-fx)))
  (is (= :owner-follow (hook-catalog/ray-impl-key "mine-ray-basic")))
  (is (= :owner-follow-marker (hook-catalog/marker-impl-key :tp-marking)))
  (is (= :owner-follow-marker (hook-catalog/marker-impl-key "marker")))
  (is (nil? (hook-catalog/effect-impl-key :unknown-hook)))
  (is (nil? (hook-catalog/ray-impl-key :unknown-hook)))
  (is (nil? (hook-catalog/marker-impl-key :unknown-hook))))

(deftest install-resolvers-test
  (hook-catalog/install-resolvers!)
  (is (= :owner-offset (hook-resolver/resolve-impl-key :effect "diamond-shield")))
  (is (= :owner-follow (hook-resolver/resolve-impl-key :ray "railgun-fx")))
  (is (= :owner-follow-marker (hook-resolver/resolve-impl-key :marker "tp-marking"))))
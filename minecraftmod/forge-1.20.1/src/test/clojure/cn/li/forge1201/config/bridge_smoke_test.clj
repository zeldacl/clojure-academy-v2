(ns cn.li.forge1201.config.bridge-smoke-test
  "Basic smoke tests for config bridge module functions.
  These tests verify that config functions can be called without errors."
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.config.gameplay :as gameplay]
            [cn.li.forge1201.config.bridge :as bridge]))

(deftest gameplay-descriptors-are-ac-owned
  (testing "AC gameplay descriptors include platform-supported list types"
    (is (seq gameplay/descriptors))
    (is (some #(= :string-list (:type %)) gameplay/descriptors))
    (is (some #(= :int-list (:type %)) gameplay/descriptors))))

(deftest config-storage-is-initialized
  (testing "registered-configs atom exists and is a map"
    (is (contains? (ns-publics 'cn.li.forge1201.config.bridge) 'registered-configs))
    (is (map? @bridge/registered-configs))))

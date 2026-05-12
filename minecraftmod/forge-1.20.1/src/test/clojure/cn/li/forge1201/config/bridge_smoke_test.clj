(ns cn.li.forge1201.config.bridge-smoke-test
  "Basic smoke tests for config bridge module functions.
  These tests verify that config functions can be called without errors."
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.forge1201.config.bridge :as bridge]))

(deftest gameplay-config-getters-are-callable
  "Verify that all gameplay config getter functions exist and are callable"
  
  (testing "boolean config getters"
    (is (fn? bridge/analysis-enabled?))
    (is (fn? bridge/attack-player?))
    (is (fn? bridge/destroy-blocks?))
    (is (fn? bridge/gen-ores?))
    (is (fn? bridge/gen-phase-liquid?))
    (is (fn? bridge/heads-or-tails?)))
  
  (testing "list config getters"
    (is (fn? bridge/get-normal-metal-blocks))
    (is (fn? bridge/get-weak-metal-blocks))
    (is (fn? bridge/get-metal-entities)))
  
  (testing "numeric config getters"
    (is (fn? bridge/get-cp-recover-cooldown))
    (is (fn? bridge/get-cp-recover-speed))
    (is (fn? bridge/get-overload-recover-cooldown))
    (is (fn? bridge/get-overload-recover-speed)))
  
  (testing "list accessor getters"
    (is (fn? bridge/get-init-cp-list))
    (is (fn? bridge/get-add-cp-list))
    (is (fn? bridge/get-init-overload-list))
    (is (fn? bridge/get-add-overload-list)))
  
  (testing "level-based getters"
    (is (fn? bridge/get-init-cp))
    (is (fn? bridge/get-add-cp))
    (is (fn? bridge/get-init-overload))
    (is (fn? bridge/get-add-overload))))

(deftest config-helper-functions-exist
  "Verify that config helper and predicate functions are available"
  
  (testing "metal block predicates"
    (is (fn? bridge/is-metal-block?))
    (is (fn? bridge/is-normal-metal-block?))
    (is (fn? bridge/is-weak-metal-block?)))
  
  (testing "metal entity predicates"
    (is (fn? bridge/is-metal-entity?))))

(deftest config-storage-is-initialized
  "Verify that config storage is properly set up"
  
  (testing "registered-configs atom exists and is a map"
    (is (contains? bridge 'registered-configs))
    (is (map? @bridge/registered-configs))))

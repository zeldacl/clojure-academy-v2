(ns cn.li.forge1201.config.bridge-smoke-test
  "Basic smoke tests for config bridge module functions.
  These tests verify that config functions can be called without errors."
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.forge1201.config.bridge :as bridge]
            [cn.li.forge1201.config.game-config :as game-config]))

(deftest gameplay-config-getters-are-callable
  (testing "boolean config getters"
    (is (fn? game-config/attack-player?))
    (is (fn? game-config/destroy-blocks?)))
  
  (testing "list config getters"
    (is (fn? game-config/get-normal-metal-blocks))
    (is (fn? game-config/get-weak-metal-blocks))
    (is (fn? game-config/get-metal-entities)))
  
  (testing "numeric config getters"
    (is (fn? game-config/get-cp-recover-cooldown))
    (is (fn? game-config/get-cp-recover-speed))
    (is (fn? game-config/get-overload-recover-cooldown))
    (is (fn? game-config/get-overload-recover-speed)))
  
  (testing "list accessor getters"
    (is (fn? game-config/get-init-cp-list))
    (is (fn? game-config/get-add-cp-list))
    (is (fn? game-config/get-init-overload-list))
    (is (fn? game-config/get-add-overload-list)))
  
  (testing "level-based getters"
    (is (fn? game-config/get-init-cp))
    (is (fn? game-config/get-add-cp))
    (is (fn? game-config/get-init-overload))
    (is (fn? game-config/get-add-overload))))

(deftest config-helper-functions-exist
  (testing "metal block predicates"
    (is (fn? game-config/is-metal-block?))
    (is (fn? game-config/is-normal-metal-block?))
    (is (fn? game-config/is-weak-metal-block?)))
  
  (testing "metal entity predicates"
    (is (fn? game-config/is-metal-entity?))))

(deftest config-storage-is-initialized
  (testing "registered-configs atom exists and is a map"
    (is (contains? (ns-publics 'cn.li.forge1201.config.bridge) 'registered-configs))
    (is (map? @bridge/registered-configs))))

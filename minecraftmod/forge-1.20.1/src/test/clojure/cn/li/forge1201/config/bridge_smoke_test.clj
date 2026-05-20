(ns cn.li.forge1201.config.bridge-smoke-test
  "Basic smoke tests for config bridge module functions.
  These tests verify that config functions can be called without errors."
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.ability.config :as ability]
            [cn.li.ac.config.common :as config-common]
            [cn.li.ac.config.gameplay :as gameplay]
            [cn.li.forge1201.config.bridge :as bridge]))

(deftest gameplay-descriptors-are-ac-owned
  (testing "AC gameplay descriptors include platform-supported UI primitive types"
    (is (seq gameplay/descriptors))
    (is (some #(= :boolean (:type %)) gameplay/descriptors))
    (is (some #(= :string (:type %)) gameplay/descriptors))))

(deftest ability-descriptors-are-ac-owned
  (testing "AC ability descriptors include platform-supported primitive and list types"
    (is (seq ability/descriptors))
    (is (some #(= :boolean (:type %)) ability/descriptors))
    (is (some #(= :string-list (:type %)) ability/descriptors))
    (is (some #(= :double-list (:type %)) ability/descriptors))))

(deftest ability-domain-builds-forge-external-file-contract
  (testing "ability domain maps to the player-facing Forge TOML file"
    (let [domain->file-name @(ns-resolve 'cn.li.forge1201.config.bridge 'domain->file-name)
          build-domain-spec @(ns-resolve 'cn.li.forge1201.config.bridge 'build-domain-spec)
          domain-info (build-domain-spec config-common/ability-domain ability/descriptors)]
      (is (= "cn.li.ac-ability.toml"
             (domain->file-name config-common/ability-domain ".toml")))
      (is (= config-common/ability-domain (:domain domain-info)))
      (is (= "cn.li.ac-ability.toml" (:file-name domain-info)))
      (is (= (set (keys ability/default-values))
             (set (keys (:entries domain-info))))))))

(deftest config-storage-is-initialized
  (testing "registered-configs atom exists and is a map"
    (is (contains? (ns-publics 'cn.li.forge1201.config.bridge) 'registered-configs))
    (is (map? @bridge/registered-configs))))

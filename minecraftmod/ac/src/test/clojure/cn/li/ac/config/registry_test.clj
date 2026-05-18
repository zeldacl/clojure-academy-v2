(ns cn.li.ac.config.registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.block.solar-gen.config :as solar-config]
            [cn.li.ac.config.common :as config-common]
            [cn.li.ac.config.gameplay :as gameplay-config]
            [cn.li.ac.config.registry :as registry]
            [cn.li.ac.wireless.config :as wireless-config]
            [cn.li.mcmod.config.registry :as config-reg]))

(deftest wireless-descriptor-aggregation-test
  (testing "registry includes every descriptor from all wireless modules"
    (let [expected-count (+ (count wireless-config/descriptors)
                            (count solar-config/descriptors))]
      (is (= expected-count (count registry/wireless-descriptors)))
      (is (= expected-count (count (set (map :key registry/wireless-descriptors))))))))

(deftest wireless-default-values-merge-test
  (testing "registry default map is the same merge contract as source modules"
    (let [expected (merge wireless-config/default-values
                          solar-config/default-values)]
      (is (= expected registry/wireless-default-values)))))

(deftest init-configs-registers-descriptors-and-defaults-test
  (testing "init-configs! registers descriptors then defaults for AC config domains"
    (let [calls (atom [])]
      (with-redefs [config-reg/register-config-descriptors!
                    (fn [domain descriptors]
                      (swap! calls conj [:register domain descriptors]))
                    config-reg/ensure-default-values!
                    (fn [domain defaults]
                      (swap! calls conj [:defaults domain defaults]))]
        (is (nil? (registry/init-configs!)))
        (is (= [[:register config-common/wireless-domain registry/wireless-descriptors]
                [:defaults config-common/wireless-domain registry/wireless-default-values]
                [:register config-common/gameplay-domain gameplay-config/descriptors]
                [:defaults config-common/gameplay-domain gameplay-config/default-values]]
               @calls))))))

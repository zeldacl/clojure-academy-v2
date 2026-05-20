(ns cn.li.ac.config.registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.ability.config :as ability-config]
            [cn.li.ac.ability.skill-config :as ability-skill-config]
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
        (is (= (vec
                 (concat
                   [[:register config-common/wireless-domain registry/wireless-descriptors]
                    [:defaults config-common/wireless-domain registry/wireless-default-values]
                    [:register config-common/gameplay-domain gameplay-config/descriptors]
                    [:defaults config-common/gameplay-domain gameplay-config/default-values]
                    [:register config-common/ability-domain ability-config/descriptors]
                    [:defaults config-common/ability-domain ability-config/default-values]]
                   (mapcat (fn [category-id]
                             (let [domain (ability-skill-config/category-domain category-id)]
                               [[:register domain (get ability-skill-config/descriptors-by-category category-id)]
                                [:defaults domain (get ability-skill-config/default-values-by-category category-id)]]))
                           ability-skill-config/category-ids)))
               @calls))))))

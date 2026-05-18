(ns cn.li.ac.wireless.config-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.config.common :as config-common]
            [cn.li.ac.wireless.config :as wireless-config]
            [cn.li.mcmod.config.registry :as config-reg]))

(deftest default-values-contract-test
  (testing "default-values mirrors descriptor defaults"
    (is (= (set (map :key wireless-config/descriptors))
           (set (keys wireless-config/default-values))))
    (doseq [{:keys [key default]} wireless-config/descriptors]
      (is (= default (get wireless-config/default-values key))))))

(deftest runtime-config-override-and-fallback-test
  (testing "network runtime getters use overrides and defaults"
    (with-redefs [config-reg/get-config-values
                  (fn [domain]
                    (is (= config-common/wireless-domain domain))
                    {:network-update-interval-ticks 77})]
      (is (= 77 (wireless-config/update-interval-ticks)))
      (is (= 2000.0 (wireless-config/buffer-max)))))
  (testing "empty config map falls back to built-in network defaults"
    (with-redefs [config-reg/get-config-values (fn [_] {})]
      (is (= 40 (wireless-config/update-interval-ticks)))
      (is (= 2000.0 (wireless-config/buffer-max))))))

(deftest search-config-override-and-fallback-test
  (testing "search getters return explicit override values when present"
    (with-redefs [config-reg/get-config-values
                  (fn [domain]
                    (is (= config-common/wireless-domain domain))
                    {:search-node-range 48.0
                     :search-max-results 333})]
      (is (= 48.0 (wireless-config/node-search-range)))
      (is (= 333 (wireless-config/max-results)))))
  (testing "missing search key in config keeps the module default"
    (with-redefs [config-reg/get-config-values (fn [_] {:search-max-results 256})]
      (is (= 20.0 (wireless-config/node-search-range)))
      (is (= 256 (wireless-config/max-results))))))
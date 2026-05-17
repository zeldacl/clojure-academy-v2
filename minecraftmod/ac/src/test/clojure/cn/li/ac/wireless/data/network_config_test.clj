(ns cn.li.ac.wireless.data.network-config-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.config.common :as config-common]
            [cn.li.ac.wireless.config :as network-config]
            [cn.li.mcmod.config.registry :as config-reg]))

(deftest default-values-contract-test
  (testing "default-values mirrors descriptor defaults"
    (is (= (set (map :key network-config/descriptors))
           (set (keys network-config/default-values))))
    (doseq [{:keys [key default]} network-config/descriptors]
      (is (= default (get network-config/default-values key))))))

(deftest config-override-and-fallback-test
  (testing "runtime getters use config overrides and fallback to defaults"
    (with-redefs [config-reg/get-config-values
                  (fn [domain]
                    (is (= config-common/wireless-domain domain))
                    {:network-update-interval-ticks 77})]
      (is (= 77 (network-config/update-interval-ticks)))
      (is (= 2000.0 (network-config/buffer-max)))))
  (testing "empty config map falls back to built-in defaults"
    (with-redefs [config-reg/get-config-values (fn [_] {})]
      (is (= 40 (network-config/update-interval-ticks)))
      (is (= 2000.0 (network-config/buffer-max))))))

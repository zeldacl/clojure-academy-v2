(ns cn.li.ac.wireless.search-config-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.config.common :as config-common]
            [cn.li.ac.wireless.search-config :as search-config]
            [cn.li.mcmod.config.registry :as config-reg]))

(deftest default-values-contract-test
  (testing "default-values keeps one entry for each descriptor key"
    (is (= (set (map :key search-config/descriptors))
           (set (keys search-config/default-values))))
    (doseq [{:keys [key default]} search-config/descriptors]
      (is (= default (get search-config/default-values key))))))

(deftest config-override-and-fallback-test
  (testing "getters return explicit override values when present"
    (with-redefs [config-reg/get-config-values
                  (fn [domain]
                    (is (= config-common/wireless-domain domain))
                    {:search-node-range 48.0
                     :search-max-results 333})]
      (is (= 48.0 (search-config/node-search-range)))
      (is (= 333 (search-config/max-results)))))
  (testing "missing key in config keeps the module default"
    (with-redefs [config-reg/get-config-values (fn [_] {:search-max-results 256})]
      (is (= 20.0 (search-config/node-search-range)))
      (is (= 256 (search-config/max-results))))))

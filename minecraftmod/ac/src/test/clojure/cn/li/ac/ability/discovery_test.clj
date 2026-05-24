(ns cn.li.ac.ability.discovery-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.discovery :as discovery]
            [cn.li.ac.discovery.registry :as registry]
            [cn.li.ac.discovery.scanner :as scanner]))

(defn- reset-discovery-state! [f]
  (let [attempts* (var-get #'cn.li.ac.ability.discovery/bootstrap-attempts*)]
    (registry/clear-providers!)
    (reset! attempts* 0)
    (try
      (f)
      (finally
        (registry/clear-providers!)
        (reset! attempts* 0)))))

(use-fixtures :each reset-discovery-state!)

(deftest fallback-electromaster-fx-includes-arc-gen-fx-test
  (with-redefs [scanner/discover-ability-providers (fn [] [])]
    (let [fx-ns (set (discovery/discovered-fx-namespaces))]
      (is (contains? fx-ns 'cn.li.ac.content.ability.electromaster.arc-gen-fx)))))

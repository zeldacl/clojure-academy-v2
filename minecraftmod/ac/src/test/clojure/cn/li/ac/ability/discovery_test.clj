(ns cn.li.ac.ability.discovery-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.discovery :as discovery]
            [cn.li.ac.discovery.scanner :as scanner]))

(deftest discovered-skill-namespaces-uses-scanner-output-test
  (with-redefs [scanner/discover-ability-namespaces
                (fn []
                  {:all ['cn.li.ac.content.ability.electromaster.railgun]
                   :skill ['cn.li.ac.content.ability.electromaster.railgun]
                   :fx ['cn.li.ac.content.ability.electromaster.railgun-fx]})]
    (is (= ['cn.li.ac.content.ability.electromaster.railgun]
           (discovery/discovered-skill-namespaces)))))

(deftest discovered-fx-namespaces-uses-scanner-output-test
  (with-redefs [scanner/discover-ability-namespaces
                (fn []
                  {:all ['cn.li.ac.content.ability.electromaster.railgun-fx]
                   :skill []
                   :fx ['cn.li.ac.content.ability.electromaster.railgun-fx]})]
    (is (= ['cn.li.ac.content.ability.electromaster.railgun-fx]
           (discovery/discovered-fx-namespaces)))))

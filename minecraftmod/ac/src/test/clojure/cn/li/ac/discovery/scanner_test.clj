(ns cn.li.ac.discovery.scanner-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.discovery.scanner :as scanner]))

(deftest discover-ability-namespaces-uses-scanner-output-test
  (testing "scanner discovers current workspace ability namespaces"
    (let [{:keys [all skill fx]} (scanner/discover-ability-namespaces)]
      (is (pos? (count all)))
      (is (some #(= 'cn.li.ac.content.ability.electromaster.railgun %) skill))
      (is (some #(= 'cn.li.ac.content.ability.electromaster.railgun-fx %) fx))
      (is (every? #(not= 'cn.li.ac.content.ability %) all)))))

(deftest discover-ability-providers-groups-by-family-and-priority-test
  (with-redefs [scanner/discover-ability-namespaces
                (fn []
                  {:all ['cn.li.ac.content.ability.electromaster.railgun
                         'cn.li.ac.content.ability.electromaster.railgun-fx]
                   :skill ['cn.li.ac.content.ability.electromaster.railgun]
                   :fx ['cn.li.ac.content.ability.electromaster.railgun-fx]})]
    (let [providers (scanner/discover-ability-providers)
          electromaster (first (filter #(= :electromaster (:id %)) providers))]
      (is (some? electromaster))
      (is (= ['cn.li.ac.content.ability.electromaster.railgun]
             (:skill-namespaces electromaster)))
      (is (= ['cn.li.ac.content.ability.electromaster.railgun-fx]
             (:fx-namespaces electromaster))))))

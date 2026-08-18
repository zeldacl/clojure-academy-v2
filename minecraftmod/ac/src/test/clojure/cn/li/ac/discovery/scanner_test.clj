(ns cn.li.ac.discovery.scanner-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cn.li.ac.discovery.scanner :as scanner]))

(deftest discover-ability-namespaces-uses-scanner-outline-test
  (testing "scanner discovers current classpath ability namespaces"
    (let [{:keys [all skill fx]} (scanner/discover-ability-namespaces)
          path (fn [sym] (str/replace (str sym) #"\\" "/"))
          brain-course? (fn [sym] (= "cn.li.ac.content.ability.generic/brain-course" (path sym)))]
      (is (pos? (count all)))
      (is (some brain-course? skill))
      (is (some #(= "cn.li.ac.content.ability.electromaster/thunder-bolt-fx" (path %)) fx))
      (is (every? #(re-find #"cn\.li\.ac\.content\.ability" (path %)) all)))))

(deftest discover-ability-providers-groups-by-family-and-layout-test
  (with-redefs [scanner/discover-ability-namespaces
                (fn []
                  {:all ['cn.li.ac.content.ability.electromaster/thunder-bolt
                         'cn.li.ac.content.ability.electromaster/thunder-bolt-fx]
                   :skill ['cn.li.ac.content.ability.electromaster/thunder-bolt]
                   :fx ['cn.li.ac.content.ability.electromaster/thunder-bolt-fx]})]
    (let [providers (scanner/discover-ability-providers)
          electromaster (first (filter #(= :electromaster (:id %)) providers))]
      (is (some? electromaster))
      (is (= ['cn.li.ac.content.ability.generic/brain-course]
             (:skill-namespaces electromaster)))
      (is (= ['cn.li.ac.content.ability.electromaster/thunder-bolt-fx]
             (:fx-namespaces electromaster))))))

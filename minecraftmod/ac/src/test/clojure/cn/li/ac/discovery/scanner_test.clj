(ns cn.li.ac.discovery.scanner-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cn.li.ac.discovery.scanner :as scanner]))

(deftest discover-ability-namespaces-uses-scanner-outline-test
  (testing "scanner discovers current classpath ability namespaces"
    (let [{:keys [all skill fx]} (scanner/discover-ability-namespaces)
          path (fn [sym] (str/replace (str sym) #"\\" "/"))]
      (is (pos? (count all)))
      (is (not-any? #(= "cn.li.ac.content.ability.generic/brain-course" (path %)) skill))
      ;; meltdowner/mine-ray-fx no longer exists -- meltdowner's VFX moved to
      ;; declarative .edn manifests during the node-language migration, so
      ;; no "-fx" Clojure namespace currently exists under
      ;; cn.li.ac.content.ability.* at all. Assert the classification
      ;; invariant scanner/core's fx-namespace? implements (every :fx entry
      ;; ends in "-fx", every :skill entry does not) against whatever real
      ;; content is on the classpath right now, instead of a specific example
      ;; that has since been removed.
      (is (every? #(str/ends-with? (name %) "-fx") fx))
      (is (not-any? #(str/ends-with? (name %) "-fx") skill))
      (is (every? #(re-find #"cn\.li\.ac\.content\.ability" (path %)) all)))))

(deftest discover-ability-providers-groups-by-family-and-layout-test
  (with-redefs [scanner/discover-ability-namespaces
                (fn []
                  {:all ['cn.li.ac.content.ability.meltdowner/mine-ray-fx]
                   :skill []
                   :fx ['cn.li.ac.content.ability.meltdowner/mine-ray-fx]})]
    (let [providers (scanner/discover-ability-providers)
          meltdowner (first (filter #(= :meltdowner (:id %)) providers))]
      (is (some? meltdowner))
      (is (= []
             (:skill-namespaces meltdowner)))
      (is (= ['cn.li.ac.content.ability.meltdowner/mine-ray-fx]
             (:fx-namespaces meltdowner))))))


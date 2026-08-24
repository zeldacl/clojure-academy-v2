(ns cn.li.ac.ability.final-catalog-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.ability.final-catalog :as catalog]
            [cn.li.ac.ability.final-catalog-service :as service]))

(deftest complete-resource-catalog-test
  (let [result (catalog/assemble)]
    (is (= {:combat-sources 39
            :combat-registrations 50
            :vfx-effects 36}
           (:counts result)))
    (is (= 39 (count (get-in result [:combat :sources]))))
    (is (= 50 (count (get-in result [:combat :registrations]))))
    (is (= 36 (count (get-in result [:vfx :effects]))))
    (is (string? (:content-hash result)))
    (is (every? #(contains? % :bindings)
                (get-in result [:combat :registrations])))))

(deftest typed-registration-rejects-deep-overrides-test
  (testing "the final ABI does not silently deep-merge legacy maps"
    (is (thrown? clojure.lang.ExceptionInfo
                 (catalog/compile-registration
                   {:id :special :resource "x" :overrides {:runtime {:x 1}}}
                   {:id :source :program {}})))))

(deftest vfx-catalog-has-stable-effect-hashes-test
  (let [result (catalog/assemble)]
    (is (every? (fn [[id descriptor]]
                  (and (keyword? id)
                       (= id (:id descriptor))
                       (contains? descriptor :parameters)))
                (get-in result [:vfx :effects])))))

(deftest final-service-exposes-explicit-migration-state-test
  (let [result (service/initialize!)]
    (is (= :ready (:status result)))
    (is (= 50 (+ (:ready-count result) (:pending-count result))))
    (is (pos? (:pending-count result)))
    (is (string? (service/content-hash)))
    (is (map? (service/migration-status)))
    (is (false? (service/available? :ability/electron-missile)))))

(deftest final-service-migration-report-is-complete-test
  (service/initialize!)
  (let [report (service/migration-report)]
    (is (= 50 (:total report)))
    (is (= 50 (+ (:ready report) (:pending report))))
    (is (= 50 (count (:entries report))))
    (is (every? #(contains? % :status) (:entries report)))))

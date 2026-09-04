(ns cn.li.ac.ability.final-catalog-test
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
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

(deftest damage-reactions-are-lowered-to-final-policies-test
  (let [sources (get-in (catalog/assemble) [:combat :sources])
        policy-sources (filter #(seq (:damage-policies %)) (vals sources))]
    (is (= 6 (count policy-sources)))
    (is (every? #(nil? (:reactions %)) (vals sources)))
    (is (every? #(seq (:damage-policies %)) policy-sources))))

(deftest catalog-uses-node-core-composite-expander-test
  (testing "AC never leaves a mid-layer composite in an executable graph"
    (let [assembled (catalog/assemble)
          composite-ids (set (concat (keys (get-in assembled [:combat :composites]))
                                     (keys (get-in assembled [:vfx :composites]))))
          components (fn components [value]
                       (cond
                         (map? value)
                         (into (cond-> #{}
                                 (:component value) (conj (:component value)))
                               (mapcat components (vals value)))
                         (sequential? value) (into #{} (mapcat components value))
                         :else #{}))
          graphs (concat (map :graph (vals (get-in assembled [:combat :sources])))
                         (map :control-graph (vals (get-in assembled [:vfx :effects]))))]
      (is (empty? (set/intersection composite-ids
                                    (apply set/union #{} (map components graphs))))))))

(deftest final-service-exposes-catalog-state-test
  (let [result (service/initialize!)]
    (is (= :ready (:status result)))
    (is (= 50 (count (get-in result [:combat :registrations]))))
    (is (string? (service/content-hash)))
    (is (map? (service/catalog-status)))
    (is (true? (service/available? :electron-missile)))))
(deftest final-service-catalog-report-is-complete-test
  (service/initialize!)
  (let [report (service/catalog-report)]
    (is (= 50 (:total report)))
    (is (= 50 (:graphed report)))
    (is (= 50 (count (:entries report))))
    (is (every? #(contains? % :graph) (:entries report)))))


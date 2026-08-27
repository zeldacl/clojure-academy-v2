(ns cn.li.ac.ability.service.final-cutover-guard-test
  "Final-architecture regression guards.

   The former tests in this directory exercised namespaces removed by the
   Combat Core cut-over. These checks target only the current Final catalog
   and cannot accidentally re-introduce a legacy evaluator as a dependency."
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.final-catalog :as catalog]
            [cn.li.ac.ability.final-catalog-service :as service]))

(deftest final-catalog-has-only-final-registrations-test
  (let [assembled (catalog/assemble)]
    (is (= 50 (count (get-in assembled [:combat :registrations]))))
    (is (every? #(= :final (:engine %))
                (get-in assembled [:combat :registrations])))))

(deftest final-service-compiles-every-registration-test
  (let [result (service/initialize!)]
    (is (= :ready (:status result)))
    (is (= 50 (:ready-count result)))
    (is (every? #(map? (:compiled %))
                (get-in result [:combat :registrations])))))
(ns cn.li.node.validate-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.node.descriptor :as registry]
            [cn.li.node.validate :as validate]))

(use-fixtures :each
  (fn [f]
    (registry/reset-for-test!)
    (registry/register-primitive!
     {:id :test/leaf :revision 1
      :inputs {:amount {:type :double} :label {:type :string :default "x"}}
      :impl (fn [_ _] {})})
    (registry/register-primitive!
     {:id :test/wrapper :revision 1
      :children {:child {:kind :single}}
      :impl (fn [_ _] {})})
    (registry/register-primitive!
     {:id :test/with-callback :revision 1
      :inputs {:on-each {:type :node :scope {}}}
      :impl (fn [_ _] {})})
    (f)
    (registry/reset-for-test!)))

(deftest valid-node-passes-test
  (is (nil? (validate/validate! {:component :test/leaf :amount 5.0}))))

(deftest missing-required-field-throws-test
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"missing-required-field"
       (validate/validate! {:component :test/leaf}))))

(deftest default-field-may-be-omitted-test
  (is (nil? (validate/validate! {:component :test/leaf :amount 5.0}))))

(deftest unknown-field-throws-test
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"unknown-field"
       (validate/validate! {:component :test/leaf :amount 5.0 :bogus 1}))))

(deftest type-mismatch-throws-test
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"type-mismatch"
       (validate/validate! {:component :test/leaf :amount "not-a-number"}))))

(deftest deferred-value-skips-type-check-test
  (is (nil? (validate/validate! {:component :test/leaf :amount {:ref [:local :x]}}))))

(deftest unknown-component-throws-test
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"unknown-component"
       (validate/validate! {:component :test/does-not-exist}))))

(deftest recurses-into-children-and-callback-inputs-test
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"missing-required-field"
       (validate/validate! {:component :test/wrapper :child {:component :test/leaf}})))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"missing-required-field"
       (validate/validate! {:component :test/with-callback :on-each {:component :test/leaf}}))))

(ns cn.li.node.validate-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.node.environment :as environment]
            [cn.li.node.validate :as validate]))

(def ^:private test-environment
  (environment/build
   {:descriptors
    [{:id :test/leaf :revision 1 :layer :primitive
      :inputs {:amount {:type :float} :label {:type :string :default "x"}}
      :impl (fn [_ _] {})}
     {:id :test/wrapper :revision 1 :layer :primitive
      :children {:child {:kind :single}}
      :impl (fn [_ _] {})}
     {:id :test/with-callback :revision 1 :layer :primitive
      :inputs {:on-each {:type :node :scope {}}}
      :impl (fn [_ _] {})}]}))

(defn- validate! [node]
  (validate/validate-in-environment! test-environment node))

(deftest valid-node-passes-test
  (is (nil? (validate! {:component :test/leaf :amount 5.0}))))

(deftest missing-required-field-throws-test
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"missing-required-field"
       (validate! {:component :test/leaf}))))

(deftest default-field-may-be-omitted-test
  (is (nil? (validate! {:component :test/leaf :amount 5.0}))))

(deftest unknown-field-throws-test
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"unknown-field"
       (validate! {:component :test/leaf :amount 5.0 :bogus 1}))))

(deftest type-mismatch-throws-test
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"type-mismatch"
       (validate! {:component :test/leaf :amount "not-a-number"}))))

(deftest deferred-value-skips-type-check-test
  (is (nil? (validate! {:component :test/leaf :amount {:ref [:local :x]}}))))

(deftest unknown-component-throws-test
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"unknown-component"
       (validate! {:component :test/does-not-exist}))))

(deftest recurses-into-children-and-callback-inputs-test
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"missing-required-field"
       (validate! {:component :test/wrapper :child {:component :test/leaf}})))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"missing-required-field"
       (validate! {:component :test/with-callback :on-each {:component :test/leaf}}))))


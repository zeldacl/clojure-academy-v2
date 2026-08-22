(ns cn.li.node.descriptor-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.node.descriptor :as registry]))

(use-fixtures :each (fn [f] (registry/reset-for-test!) (f) (registry/reset-for-test!)))

(deftest register-primitive-requires-impl-test
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"primitive component must have :impl"
       (registry/register-primitive! {:id :test/no-impl :revision 1}))))

(deftest register-composite-rejects-impl-test
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"mid-layer component must not have :impl"
       (registry/register-composite! {:id :test/fake-mid :revision 1 :layer :mid :impl (fn [_ _] nil)}))))

(deftest register-source-rejects-impl-test
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"source component must not have :impl"
       (registry/register-composite! {:id :test/fake-source :revision 1 :layer :source :impl (fn [_ _] nil)}))))

(deftest duplicate-id-rejected-test
  (registry/register-primitive! {:id :test/dup :revision 1 :impl (fn [_ _] {})})
  (is (thrown? clojure.lang.ExceptionInfo
               (registry/register-primitive! {:id :test/dup :revision 1 :impl (fn [_ _] {})}))))

(deftest frozen-registry-rejects-new-registration-test
  (registry/freeze!)
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"node registry is frozen"
       (registry/register-primitive! {:id :test/too-late :revision 1 :impl (fn [_ _] {})}))))

(deftest lookup-and-primitive-count-test
  (registry/register-primitive! {:id :test/a :revision 1 :impl (fn [_ _] {})})
  (registry/register-composite! {:id :test/b :revision 1 :layer :mid :body {:component :test/a}})
  (is (= :test/a (:id (registry/descriptor :test/a))))
  (is (nil? (registry/descriptor :test/missing)))
  (is (= 1 (registry/primitive-count))))

(deftest invalid-layer-rejected-test
  (is (thrown? clojure.lang.ExceptionInfo
               (registry/register-composite! {:id :test/bad-layer :revision 1 :layer :nonsense}))))

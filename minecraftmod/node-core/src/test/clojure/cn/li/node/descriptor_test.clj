(ns cn.li.node.descriptor-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.node.descriptor :as descriptor]
            [cn.li.node.environment :as environment]))

(deftest normalize-validates-and-fills-default-maps-test
  (let [d (descriptor/normalize {:id :test/a :revision 1 :layer :primitive
                                 :inputs {:value {:type :any}}
                                 :impl (fn [_ _] nil)})]
    (is (= :test/a (:id d)))
    (is (= {} (:outputs d)))
    (is (= {} (:children d)))
    (is (= #{} (:effects d)))))

(deftest normalize-rejects-invalid-layer-and-missing-implementation-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid node descriptor"
                        (descriptor/normalize {:id :bad :revision 1 :layer :nope})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"primitive descriptor"
                        (descriptor/normalize {:id :bad :revision 1 :layer :primitive}))))

(deftest environment-is-immutable-and-detects-duplicates-test
  (let [a {:id :test/a :revision 1 :layer :primitive :impl (fn [_ _] nil)}
        b {:id :test/b :revision 1 :layer :composite :body {}}
        env (environment/build {:descriptors [a b]})]
    (is (= :test/a (:id (environment/descriptor env :test/a))))
    (is (= 1 (environment/primitive-count env)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (environment/build {:descriptors [a a]})))))

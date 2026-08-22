(ns cn.li.node.schema-export-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.node.descriptor :as registry]
            [cn.li.node.schema-export :as export]))

(use-fixtures :each
  (fn [f]
    (registry/reset-for-test!)
    (registry/register-primitive!
     {:id :test/a :revision 1 :doc "does a thing" :category :test
      :inputs {:amount {:type :double :min 0.0 :max 10.0 :default 1.0 :doc "how much"}}
      :outputs {:result {:type :double :doc "the result"}}
      :effects #{:pure}
      :impl (fn [_ _] {:result 1.0})})
    (f)
    (registry/reset-for-test!)))

(deftest export-includes-full-field-metadata-test
  (let [[entry] (export/export-catalog)]
    (is (= :test/a (:id entry)))
    (is (= :primitive (:layer entry)))
    (is (= "does a thing" (:doc entry)))
    (is (= :test (:category entry)))
    (is (= #{:pure} (:effects entry)))
    (is (= {:type :double :min 0.0 :max 10.0 :default 1.0 :doc "how much"}
           (get-in entry [:inputs :amount])))
    (is (= {:type :double :doc "the result"} (get-in entry [:outputs :result])))))

(deftest export-excludes-impl-function-test
  (let [[entry] (export/export-catalog)]
    (is (not (contains? entry :impl)))))

(deftest export-is-sorted-by-id-test
  (registry/register-primitive! {:id :test/z :revision 1 :impl (fn [_ _] {})})
  (registry/register-primitive! {:id :test/b :revision 1 :impl (fn [_ _] {})})
  (is (= [:test/a :test/b :test/z] (mapv :id (export/export-catalog)))))

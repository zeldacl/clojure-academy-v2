(ns cn.li.node.schema-export-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.node.environment :as environment]
            [cn.li.node.schema-export :as export]))

(def ^:private test-environment
  (environment/build
   {:descriptors
    [{:id :test/a :revision 1 :layer :primitive :visibility :author
      :doc "does a thing" :category :test
      :inputs {:amount {:type :float :min 0.0 :max 10.0 :default 1.0 :doc "how much"}
               :mode {:type :keyword :choices [:fast :safe] :default :safe}}
      :outputs {:result {:type :float :doc "the result"}}
      :effects #{:pure} :impl (fn [_ _] {:result 1.0})}
     {:id :test/z :revision 1 :layer :primitive :visibility :author
      :inputs {} :impl (fn [_ _] nil)}
     {:id :test/b :revision 1 :layer :primitive :visibility :author
      :inputs {} :impl (fn [_ _] nil)}]}))

(deftest export-includes-author-fields-and-excludes-impl-test
  (let [entries (export/export-environment test-environment)
        entry (first entries)]
    (is (= [:test/a :test/b :test/z] (mapv :id entries)))
    (is (= :primitive (:layer entry)))
    (is (= "does a thing" (:doc entry)))
    (is (= {:type :float :min 0.0 :max 10.0 :default 1.0 :doc "how much"}
           (get-in entry [:inputs :amount])))
    (is (= {:type :keyword :choices [:fast :safe] :default :safe}
           (get-in entry [:inputs :mode])))
    (is (not (contains? entry :impl)))))

(ns cn.li.node.value-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.node.value :as value]))

(deftest resolves-local-ref-test
  (is (= 5.0 (value/resolve-value {:ref [:local :x]} {:x 5.0} 0))))

(deftest resolves-local-ref-with-path-test
  (is (= 3.0 (value/resolve-value {:ref [:local :hit :position :y]} {:hit {:position {:y 3.0}}} 0))))

(deftest resolves-nested-expr-test
  (is (= 7.0 (value/resolve-value {:expr :math/add :args [{:ref [:local :a]} 2.0]} {:a 5.0} 0))))

(deftest resolves-plain-map-recursively-test
  (is (= {:x 5.0 :y 2.0} (value/resolve-value {:x {:ref [:local :a]} :y 2.0} {:a 5.0} 0))))

(deftest literal-passes-through-test
  (is (= "beam" (value/resolve-value "beam" {} 0)))
  (is (= [1 2 3] (value/resolve-value [1 2 3] {} 0))))


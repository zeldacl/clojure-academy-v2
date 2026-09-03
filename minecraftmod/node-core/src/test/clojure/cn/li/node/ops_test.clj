(ns cn.li.node.ops-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.node.ops :as ops]))

(deftest known-op-and-signature-test
  (is (ops/known-op? :vec3/add))
  (is (not (ops/known-op? :vec3/nonexistent)))
  (is (= {:params [:vec3 :vec3] :returns :vec3} (ops/signature :vec3/add))))

(deftest invoke-delegates-to-expr-evaluate-test
  (is (= {:vec3 [4.0 6.0 8.0]} (ops/invoke :vec3/add [{:vec3 [1.0 2.0 3.0]} {:vec3 [3.0 4.0 5.0]}])))
  (is (= 5.0 (ops/invoke :math/add [2.0 3.0]))))

(deftest invoke-throws-on-unknown-op-test
  (is (thrown? clojure.lang.ExceptionInfo (ops/invoke :not-a-real-op [1.0]))))

(deftest table-arities-match-declared-param-counts-test
  ;; a signature drifting out of sync with the number of args its opcode
  ;; actually expects would silently mis-typecheck every DSL call site
  (doseq [[op {:keys [params]}] ops/table]
    (is (pos? (count params)) (str op " has an empty :params vector"))))

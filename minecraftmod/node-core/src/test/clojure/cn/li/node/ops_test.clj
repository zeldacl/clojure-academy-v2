(ns cn.li.node.ops-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.node.ops :as ops]))

(deftest known-op-and-signature-test
  (is (ops/known-op? :vec3/add))
  (is (not (ops/known-op? :vec3/nonexistent)))
  (is (= {:params [:vec3 :vec3] :returns :vec3} (ops/signature :vec3/add))))

(deftest invoke-delegates-to-expr-evaluate-test
  (is (= {:vec3 [4.0 6.0 8.0]} (ops/invoke :vec3/add [{:vec3 [1.0 2.0 3.0]} {:vec3 [3.0 4.0 5.0]}])))
  (is (= 5.0 (ops/invoke :math/add [2.0 3.0]))))

(deftest math-select-is-a-generic-ternary-test
  (is (= "then" (ops/invoke :math/select [true "then" "else"])))
  (is (= "else" (ops/invoke :math/select [false "then" "else"]))))

(deftest long-arithmetic-does-not-go-through-double-test
  (is (= 3 (ops/invoke :long/add [1 2])))
  (is (= -1 (ops/invoke :long/sub [1 2])))
  (is (= 6 (ops/invoke :long/mul [2 3])))
  (is (= 1 (ops/invoke :long/min [1 2])))
  (is (= 2 (ops/invoke :long/max [1 2]))))

(deftest pair-accessors-index-a-curve-pair-value-test
  (is (= 1.5 (ops/invoke :pair/first [[1.5 2.5]])))
  (is (= 2.5 (ops/invoke :pair/second [[1.5 2.5]]))))

(deftest floor-long-truncates-and-returns-a-real-long-test
  (is (= 40 (ops/invoke :math/floor-long [40.9])))
  (is (integer? (ops/invoke :math/floor-long [40.9]))))

(deftest collection-contains-checks-membership-test
  (is (true? (ops/invoke :collection/contains? [["a" "b"] "a"])))
  (is (false? (ops/invoke :collection/contains? [["a" "b"] "c"])))
  (is (false? (ops/invoke :collection/contains? [nil "a"]))))

(deftest collection-first-and-nonempty-test
  (is (= "a" (ops/invoke :collection/first [["a" "b"]])))
  (is (nil? (ops/invoke :collection/first [[]])))
  (is (true? (ops/invoke :collection/nonempty [["a"]])))
  (is (false? (ops/invoke :collection/nonempty [[]]))))

(deftest vec3-launch-pitches-and-scales-the-direction-test
  (testing "level aim (no vertical component), zero pitch offset -> straight ahead at the given speed"
    (is (= {:vec3 [5.0 0.0 0.0]} (ops/invoke :vec3/launch [[1.0 0.0 0.0] 5.0 0.0]))))
  (testing "positive pitch-offset tilts the result upward"
    (let [{[_ y _] :vec3} (ops/invoke :vec3/launch [[1.0 0.0 0.0] 5.0 (/ Math/PI 2)])]
      (is (< 4.99 y 5.01))))
  (testing "straight-up direction has no horizontal component to pitch -- magnitude only"
    (is (= {:vec3 [0.0 3.0 0.0]} (ops/invoke :vec3/launch [[0.0 1.0 0.0] 3.0 0.5])))))

(deftest invoke-throws-on-unknown-op-test
  (is (thrown? clojure.lang.ExceptionInfo (ops/invoke :not-a-real-op [1.0]))))

(deftest table-arities-match-declared-param-counts-test
  ;; a signature drifting out of sync with the number of args its opcode
  ;; actually expects would silently mis-typecheck every DSL call site
  (doseq [[op {:keys [params]}] ops/table]
    (is (pos? (count params)) (str op " has an empty :params vector"))))

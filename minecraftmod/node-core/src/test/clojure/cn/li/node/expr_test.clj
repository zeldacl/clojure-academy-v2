(ns cn.li.node.expr-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.node.expr :as expr]))

(deftest basic-math-test
  (is (= 5.0 (expr/evaluate :math/add [2.0 3.0])))
  (is (= 6.0 (expr/evaluate :math/mul [2.0 3.0])))
  (is (= 2.0 (expr/evaluate :math/clamp [5.0 0.0 2.0])))
  (is (= 8.0 (expr/evaluate :math/pow [2.0 3.0]))))

(deftest vec3-add-test
  (is (= {:vec3 [4.0 6.0 8.0]}
         (expr/evaluate :vec3/add [{:vec3 [1.0 2.0 3.0]} {:vec3 [3.0 4.0 5.0]}]))))

(deftest vec3-normalize-zero-length-test
  (is (= {:vec3 [0.0 0.0 0.0]} (expr/evaluate :vec3/normalize [{:vec3 [0.0 0.0 0.0]}]))))

(deftest vec3-component-accessors-test
  (is (= 1.0 (expr/evaluate :vec3/x [{:vec3 [1.0 2.0 3.0]}])))
  (is (= 2.0 (expr/evaluate :vec3/y [{:vec3 [1.0 2.0 3.0]}])))
  (is (= 3.0 (expr/evaluate :vec3/z [{:vec3 [1.0 2.0 3.0]}]))))

(deftest unsupported-opcode-throws-test
  (is (thrown? clojure.lang.ExceptionInfo (expr/evaluate :math/nonexistent [1.0]))))

(deftest random-is-deterministic-per-seed-test
  (is (= (expr/evaluate :random/uniform [0.0 1.0] 42)
         (expr/evaluate :random/uniform [0.0 1.0] 42)))
  (is (not= (expr/evaluate :random/uniform [0.0 1.0] 42)
            (expr/evaluate :random/uniform [0.0 1.0] (expr/next-seed 42)))))

(deftest register-op-extends-vocabulary-test
  (expr/register-op! :test/double (fn [args _seed] (* 2.0 (double (first args)))))
  (is (= 10.0 (expr/evaluate :test/double [5.0]))))

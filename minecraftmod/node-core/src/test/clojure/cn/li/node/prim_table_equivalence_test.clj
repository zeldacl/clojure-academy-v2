(ns cn.li.node.prim-table-equivalence-test
  "cn.li.node.ops/prim-table duplicates one arithmetic expression per entry
   from cn.li.node.expr (never anything with real logic, see that table's
   own docstring) so cn.li.mcmod.runtime.effect-emit can specialize :pure
   dispatch without boxing. This is the SEMANTIC half of that guarantee:
   proves every prim-table entry computes the same value expr/evaluate
   does, for representative inputs including the edge cases the two ops
   with a real branch in their logic (:math/div's zero check, :math/floor-
   long's negative-input floor direction) actually guard.

   The MECHANICAL half -- that effect-emit's specialized dispatch path
   produces the identical ExecutionFrame as its generic path given a
   prim-ops table -- is mcmod's own effect-emit-prim-specialization-test.
   mcmod cannot depend on node-core even in test scope (see effect-emit-
   test's own docstring), so that proof necessarily lives there against a
   hand-built fixture table, not this real one. Together the two tests
   chain into one guarantee: prim-table == expr semantics (this file),
   and specialized dispatch == generic dispatch for any compatible
   prim-ops (mcmod's test) => specialized dispatch == expr semantics
   end-to-end."
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.node.ops :as ops]
            [cn.li.node.expr :as expr]))

(def ^:private sample-args
  "op-name -> a vector of representative :args tuples (matching prim-
   table's :arg-banks order/count)."
  {:math/add [[2.0 3.0] [-1.5 0.0]]
   :math/sub [[5.0 2.0] [-1.0 -1.0]]
   :math/mul [[2.0 3.0] [-1.0 4.0]]
   :math/div [[6.0 3.0] [6.0 0.0]]
   :math/min [[2.0 3.0] [3.0 2.0]]
   :math/max [[2.0 3.0] [3.0 2.0]]
   :math/pow [[2.0 3.0] [4.0 0.5]]
   :math/abs [[-2.0] [2.0]]
   :math/floor [[2.7] [-2.3]]
   :math/sqrt [[4.0] [0.0]]
   :math/sin [[0.7] [0.0]]
   :math/cos [[0.7] [0.0]]
   :math/floor-long [[2.7] [-2.3]]
   :math/clamp [[5.0 0.0 2.0] [1.0 0.0 2.0] [-1.0 0.0 2.0]]
   :math/lerp [[0.0 10.0 0.5] [10.0 0.0 0.25]]
   :math/lt [[1.0 2.0] [2.0 1.0] [2.0 2.0]]
   :math/lte [[2.0 2.0] [3.0 2.0]]
   :math/eq [[2.0 2.0] [2.0 3.0]]
   :math/gte [[2.0 2.0] [1.0 2.0]]
   :math/gt [[3.0 2.0] [2.0 2.0]]
   :long/add [[2 3] [-5 5]]
   :long/sub [[5 2] [-1 -1]]
   :long/mul [[2 3] [-2 3]]
   :long/min [[2 3] [3 2]]
   :long/max [[2 3] [3 2]]})

(deftest every-prim-table-entry-matches-expr-evaluate-test
  (doseq [[op-name {prim-fn :fn}] ops/prim-table]
    (testing (str op-name)
      (doseq [args (get sample-args op-name)]
        (is (= (expr/evaluate op-name args) (apply prim-fn args))
            (str op-name " " args))))))

(deftest sample-args-covers-every-prim-table-entry-test
  (is (= (set (keys ops/prim-table)) (set (keys sample-args)))
      "a prim-table entry with no sample-args fixture has zero equivalence
       coverage from this test -- keep this set in sync with prim-table"))

(deftest prim-table-shapes-match-declared-banks-test
  (doseq [[op-name {:keys [arg-banks dst-bank]}] ops/prim-table]
    (testing (str op-name " arg-banks/dst-bank are real cn.li.node.types banks")
      (is (every? #{:doubles :longs :booleans :objects} arg-banks))
      (is (contains? #{:doubles :longs :booleans :objects} dst-bank)))))

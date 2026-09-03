(ns cn.li.node.set-bang-test
  "set! -- found necessary while porting terrain/apply-break-budget (S6):
   an each-loop accumulator (energy budget decreasing per broken block) has
   no expression in this DSL other than reassigning an existing local each
   iteration. Implemented as a :copy into the SAME register the local
   already occupies (node-core's registers were always mutable per-bank
   slots, not true SSA -- see cn.li.node.ir's docstring), tagged :reassign?
   so cn.li.node.pretty can tell it apart from an ordinary compiler-
   internal :copy."
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.node.surface :as surface]
            [cn.li.node.compile :as compile]
            [cn.li.node.ir :as ir]
            [cn.li.node.pretty :as pretty]
            [cn.li.node.test-fixtures :as fx]))

(def ^:private budget-text
  "{:ability :budget-test :tunables {:energy {:type :double}}
    :do [(let remaining $energy)
         (let xs (target/entities {:center [0.0 0.0 0.0] :radius 4.0}))
         (each block xs
           (set! remaining (math/sub remaining 1.0)))
         (cooldown/start {:name :main :ticks 1})
         (finish {:outcome :performed})]}")

(deftest set-reassigns-an-existing-register-not-a-fresh-one-test
  (let [doc (surface/parse budget-text)
        ir (compile/compile! doc fx/opts)]
    (is (map? (ir/validate! ir)))
    (testing "the reassignment compiles to a :copy tagged :reassign? true"
      (is (some #(and (= :copy (:op %)) (:reassign? %)) (mapcat :instrs (:blocks ir)))))))

(deftest set-target-must-already-be-bound-test
  (let [doc (surface/parse
             "{:ability :bad :tunables {} :do [(set! nope 1.0) (finish {:outcome :performed})]}")]
    (try
      (compile/compile! doc fx/opts)
      (is false "expected compile! to throw")
      (catch clojure.lang.ExceptionInfo e
        (is (= :unbound-local (:code (ex-data e))))))))

(deftest pretty-printing-set-throws-a-clear-error-instead-of-silently-dropping-it-test
  (testing "a deliberate, documented gap (see this namespace's docstring and
            cn.li.node.pretty/inline-op?'s) -- must fail loudly, not print a
            program missing its own accumulator update"
    (let [doc (surface/parse budget-text)
          ir (compile/compile! doc fx/opts)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"set!"
                            (pretty/unparse ir))))))

(ns cn.li.node.pretty-test
  "IR -> DSL round trip (cn.li.node.pretty). Variable names are not
   recoverable from IR (see pretty's docstring), so the meaningful
   property to test is NOT unparse(compile(doc)) == doc textually, but
   that re-unparsing a RECOMPILED result is a fixed point: an editor that
   opens, does nothing, and saves must not keep rewriting the file on every
   open/save cycle."
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.node.surface :as surface]
            [cn.li.node.compile :as compile]
            [cn.li.node.pretty :as pretty]
            [cn.li.node.test-fixtures :as fx]))

(deftest round-trip-is-a-fixed-point-test
  (let [doc1 (surface/parse fx/thunder-bolt-text)
        ir1 (compile/compile! doc1 fx/opts)
        doc2 (pretty/unparse ir1)
        ir2 (compile/compile! doc2 fx/opts)
        doc3 (pretty/unparse ir2)]
    (testing "doc2 (the printed form of the ORIGINAL compile) recompiles cleanly"
      (is (map? ir2)))
    (testing "unparsing the recompiled IR reproduces doc2 exactly -- the
              second save does not change the file"
      (is (= doc2 doc3)))
    (testing "recompiling twice yields structurally identical IR (same
              deterministic register/nid allocation order both times)"
      (is (= ir2 (compile/compile! doc3 fx/opts))))))

(deftest round-trip-preserves-tunable-declarations-test
  (let [doc1 (surface/parse fx/thunder-bolt-text)
        ir1 (compile/compile! doc1 fx/opts)
        doc2 (pretty/unparse ir1)]
    (is (= (:tunables doc1) (:tunables doc2)))))

(deftest round-trip-on-a-branch-only-doc-test
  (let [text "{:ability :branch-only :tunables {:range {:type :double}}
               :do [(let ok (target/raycast {:from ?caster/eye :dir ?caster/eye :distance $range}))
                    (when (:entity-id ok)
                      (cooldown/start {:name :main :ticks 1}))
                    (finish {:outcome :performed})]}"
        doc1 (surface/parse text)
        ir1 (compile/compile! doc1 fx/opts)
        doc2 (pretty/unparse ir1)
        ir2 (compile/compile! doc2 fx/opts)
        doc3 (pretty/unparse ir2)]
    (is (= doc2 doc3))))

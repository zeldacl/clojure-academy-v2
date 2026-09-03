(ns cn.li.node.map-literal-test
  "Found while reading real composite content for S6 (target/penetration-
   destination's :policy, terrain/wave-plan's :spread/:energy-cost/
   :block-transforms): the old system allows an arbitrary literal EDN map
   with MIXED static and dynamic values as a field argument (e.g. :policy
   {:type :penetration :scan-step {:ref [:input :scan-step]}}), which the
   DSL had no expression form for at all until :map-lit."
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.node.surface :as surface]
            [cn.li.node.compile :as compile]
            [cn.li.node.pretty :as pretty]
            [cn.li.node.test-fixtures :as fx]))

(deftest map-literal-with-mixed-static-and-dynamic-values-compiles-test
  (let [doc (surface/parse
             "{:ability :policy-test :tunables {:step {:type :double}}
               :do [(let policy {:type :penetration :scan-step $step :clearance-steps 3})
                    (cooldown/start {:name :main :ticks 1})
                    (finish {:outcome :performed})]}")
        ir (compile/compile! doc fx/opts)]
    (testing "compiles to a :map-lit instruction"
      (is (some #(= :map-lit (:op %)) (mapcat :instrs (:blocks ir)))))))

(deftest map-literal-round-trips-test
  (let [text "{:ability :policy-test :tunables {:step {:type :double}}
              :do [(let policy {:type :penetration :scan-step $step :clearance-steps 3})
                   (cooldown/start {:name :main :ticks 1})
                   (finish {:outcome :performed})]}"
        doc1 (surface/parse text)
        ir1 (compile/compile! doc1 fx/opts)
        doc2 (pretty/unparse ir1)
        ir2 (compile/compile! doc2 fx/opts)
        doc3 (pretty/unparse ir2)]
    (is (= doc2 doc3))))

(deftest nested-map-literal-inside-a-node-call-arg-test
  (testing "the actual real-content shape: a map literal as a node call's
            OWN argument value, not just a `let` RHS"
    (let [doc (surface/parse
               "{:ability :nested :tunables {}
                 :do [(target/raycast {:from ?caster/eye :dir ?caster/eye :distance 10.0
                                       :policy {:type :penetration :clearance-steps 2}})
                      (finish {:outcome :performed})]}")
          ir (compile/compile! doc fx/opts)]
      (is (some #(= :map-lit (:op %)) (mapcat :instrs (:blocks ir)))))))

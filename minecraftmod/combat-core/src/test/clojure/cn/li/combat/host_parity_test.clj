(ns cn.li.combat.host-parity-test
  "Fail the build when a vocab node cannot be dispatched by the skill host
   (wrong query/action map, or missing entirely). This is the class of bug
   that compiled cleanly and only exploded on first real release dispatch."
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.combat.dsl-vocabulary :as vocab]
            [cn.li.combat.lib :as lib]
            [cn.li.combat.host-parity :as parity]
            [cn.li.node.graph-compile :as graph-compile]
            [cn.li.node.ops :as ops]))

(deftest vocab-capabilities-are-host-registered-test
  (let [{:keys [missing-queries missing-actions kind-mismatches]} (parity/vocab-host-gaps)]
    (is (= [] missing-queries)
        (str "vocab query capabilities missing from query-handlers (+ AC allowlist): "
             missing-queries))
    (is (= [] missing-actions)
        (str "vocab action capabilities missing from action-handlers (+ AC allowlist): "
             missing-actions))
    (is (= [] kind-mismatches)
        (str "vocab capabilities registered on the wrong query/action map: "
             kind-mismatches))))

(deftest platform-query-and-action-maps-are-disjoint-test
  (testing "a capability in both maps hides the compiler's :query vs :action choice"
    (is (= [] (parity/query-action-overlap))
        "platform query-handlers and action-handlers must not overlap")))

(deftest graph-special-components-are-exactly-the-ones-no-lookup-finds-test
  ;; The set exists to say "these resolve even though every ordinary lookup
  ;; misses". If a member ever gains a vocab/:ops/:defn entry, the entry is
  ;; the thing that should resolve it and the special case becomes dead --
  ;; and while both exist, which one wins depends on cond order in
  ;; graph-compile/component-call rather than on anything declared.
  (doseq [c parity/graph-special-components]
    (is (not (ops/known-op? c)) (str c " gained an ops signature"))
    (is (not (contains? vocab/nodes c)) (str c " gained a vocab node"))
    (is (not (contains? lib/fns c)) (str c " gained a lib :defn")))
  (testing "and the gate really is reading node-core's definition, not a copy"
    ;; It was a hand-typed duplicate; a third spelling of the same list is
    ;; how :effect/vfx's absence from the vocabulary looked like a bug.
    (is (identical? graph-compile/special-components
                    parity/graph-special-components))))

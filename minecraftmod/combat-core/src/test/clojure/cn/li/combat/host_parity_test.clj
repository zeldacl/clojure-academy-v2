(ns cn.li.combat.host-parity-test
  "Fail the build when a vocab node cannot be dispatched by the skill host
   (wrong query/action map, or missing entirely). This is the class of bug
   that compiled cleanly and only exploded on first real release dispatch."
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.combat.host-parity :as parity]))

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

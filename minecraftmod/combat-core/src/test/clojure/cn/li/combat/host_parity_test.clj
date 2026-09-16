(ns cn.li.combat.host-parity-test
  "Fail the build when a vocab node cannot be dispatched by the skill host
   (wrong query/action map, or missing entirely). This is the class of bug
   that compiled cleanly and only exploded on first real release dispatch."
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.combat.dsl-vocabulary :as vocab]
            [cn.li.combat.lib :as lib]
            [cn.li.combat.host-parity :as parity]
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

(deftest platform-query-handlers-have-engine-arity-test
  (is (= [] (parity/query-handler-arity-gaps))
      "every query handler must accept (request frame-context), the shape used by ability-runtime"))

(deftest nodes-that-need-different-handlers-do-not-share-a-capability-test
  ;; The gap the three tests above could not see. They ask whether every
  ;; capability is registered -- :raycast was. What they cannot ask is
  ;; whether ONE registered handler is being asked to serve several
  ;; different request shapes, which is what the raycast family did: five
  ;; nodes all declared :raycast, and platform/raycast! then `case`d on a
  ;; :query-kind request key that nothing in the repo ever set. Four of its
  ;; five branches were dead, so :target/raycast-fan returned a single hit
  ;; where it declares [:list-of :hit-result], and penetrate-teleport gated
  ;; on an :available? that the fallback handler does not emit.
  ;;
  ;; A shared capability is legitimate only when the nodes want the SAME
  ;; behaviour, which is a claim about their return type: the one
  ;; deliberate pair left (:target/resolve-destination and
  ;; :target/block-placement) is one query read two ways.
  (let [by-capability (->> vocab/nodes
                           (group-by (fn [[node-id spec]]
                                       (or (:capability spec) node-id)))
                           (filter (fn [[_ entries]] (< 1 (count entries)))))
        offenders (for [[capability entries] by-capability
                        :let [returns (set (map (comp :returns val) entries))]
                        :when (< 1 (count returns))]
                    {:capability capability
                     :nodes (mapv key entries)
                     :returns returns})]
    (is (= [] (vec offenders))
        (str "these nodes share one host handler but declare different"
             " return types, so the handler cannot serve all of them and"
             " nothing else will report it: " (pr-str (vec offenders))))))

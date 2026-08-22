(ns cn.li.combat.test-runner
  (:require [clojure.test :as t]
            [cn.li.combat.runtime-test]
            [cn.li.combat.recipe-test]
            [cn.li.combat.dataflow-test]
            [cn.li.combat.targeting-test]
            [cn.li.combat.reactions-test]
            [cn.li.combat.capability-coverage-test]
            [cn.li.combat.impact-event-test]
            [cn.li.combat.interception-test]
            [cn.li.combat.vfx-publish-test]
            [cn.li.combat.source-nodes-test]
            [cn.li.combat.host-primitives-test]
            [cn.li.combat.policy-primitives-test]))

(defn -main [& _]
  (let [result (t/run-tests 'cn.li.combat.runtime-test
                            'cn.li.combat.recipe-test
                            'cn.li.combat.dataflow-test
                            'cn.li.combat.targeting-test
                            'cn.li.combat.reactions-test
                            'cn.li.combat.capability-coverage-test
                            'cn.li.combat.impact-event-test
                            'cn.li.combat.interception-test
                            'cn.li.combat.vfx-publish-test
                            'cn.li.combat.source-nodes-test
                            'cn.li.combat.host-primitives-test
                            'cn.li.combat.policy-primitives-test)]
    (when (pos? (+ (:fail result) (:error result)))
      (System/exit 1))))

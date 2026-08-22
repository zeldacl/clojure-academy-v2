(ns cn.li.combat.test-runner
  (:require [clojure.test :as t]
            [cn.li.combat.runtime-test]
            [cn.li.combat.recipe-test]
            [cn.li.combat.dataflow-test]
            [cn.li.combat.targeting-test]
            [cn.li.combat.reactions-test]
            [cn.li.combat.capability-coverage-test]
            [cn.li.combat.impact-event-test]
            [cn.li.combat.interception-test]))

(defn -main [& _]
  (let [result (t/run-tests 'cn.li.combat.runtime-test
                            'cn.li.combat.recipe-test
                            'cn.li.combat.dataflow-test
                            'cn.li.combat.targeting-test
                            'cn.li.combat.reactions-test
                            'cn.li.combat.capability-coverage-test
                            'cn.li.combat.impact-event-test
                            'cn.li.combat.interception-test)]
    (when (pos? (+ (:fail result) (:error result)))
      (System/exit 1))))

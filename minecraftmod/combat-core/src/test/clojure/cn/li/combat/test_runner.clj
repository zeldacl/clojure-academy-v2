(ns cn.li.combat.test-runner
  (:require [clojure.test :as t]
            [cn.li.combat.runtime-test]
            [cn.li.combat.recipe-test]
            [cn.li.combat.dataflow-test]
            [cn.li.combat.targeting-test]))

(defn -main [& _]
  (let [result (t/run-tests 'cn.li.combat.runtime-test
                            'cn.li.combat.recipe-test
                            'cn.li.combat.dataflow-test
                            'cn.li.combat.targeting-test)]
    (when (pos? (+ (:fail result) (:error result)))
      (System/exit 1))))

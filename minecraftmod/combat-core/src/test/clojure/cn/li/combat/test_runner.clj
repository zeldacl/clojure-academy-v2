(ns cn.li.combat.test-runner
  (:require [clojure.test :as t]
            [cn.li.combat.final-engine-test]
            [cn.li.combat.final-damage-test]
            [cn.li.combat.damage-math-test]
            [cn.li.combat.dependency-direction-test]))
(defn -main [& _]
  (let [result (t/run-tests 'cn.li.combat.final-engine-test
                            'cn.li.combat.final-damage-test
                            'cn.li.combat.damage-math-test
                            'cn.li.combat.dependency-direction-test)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))

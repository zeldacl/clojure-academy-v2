(ns cn.li.combat.test-runner
  (:require [clojure.test :as t]
            [cn.li.combat.runtime-test]))

(defn -main [& _]
  (let [result (t/run-tests 'cn.li.combat.runtime-test)]
    (when (pos? (+ (:fail result) (:error result)))
      (System/exit 1))))


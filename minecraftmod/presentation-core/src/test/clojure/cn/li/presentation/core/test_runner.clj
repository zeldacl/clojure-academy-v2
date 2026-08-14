(ns cn.li.presentation.core.test-runner
  (:require [clojure.test :as test]
            [cn.li.presentation.core.core-test]))

(defn -main [& _]
  (let [result (test/run-tests 'cn.li.presentation.core.core-test)]
    (when (pos? (+ (:fail result) (:error result)))
      (System/exit 1))))

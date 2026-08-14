(ns cn.li.presentation.devtools.test-runner
  (:require [clojure.test :as test]
            [cn.li.presentation.devtools.perf-test]))

(defn -main [& _]
  (let [result (test/run-tests 'cn.li.presentation.devtools.perf-test)]
    (when (pos? (+ (:fail result) (:error result)))
      (System/exit 1))))

(ns cn.li.presentation.compiler.test-runner
  (:require [clojure.test :as test]
            [cn.li.presentation.compiler.core-test]))

(defn -main [& _]
  (let [result (test/run-tests 'cn.li.presentation.compiler.core-test)]
    (when (pos? (+ (:fail result) (:error result)))
      (System/exit 1))))

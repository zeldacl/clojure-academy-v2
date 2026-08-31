(ns cn.li.presentation.core.test-runner
  (:require [clojure.test :as test]
            [cn.li.presentation.core.runtime-test]
            [cn.li.presentation.core.composition-test]))

(defn -main [& _]
  (let [result (apply test/run-tests
                      ['cn.li.presentation.core.runtime-test
                       'cn.li.presentation.core.composition-test])]
    (when (pos? (+ (:fail result) (:error result)))
      (System/exit 1))))

(ns cn.li.presentation.core.test-runner
  (:require [clojure.test :as test]
            [cn.li.presentation.core.runtime-test]
            [cn.li.presentation.core.composition-test]
            [cn.li.presentation.core.runtime-composition-test]))

(defn -main [& _]
  (let [result (apply test/run-tests
                      ['cn.li.presentation.core.runtime-test
                       'cn.li.presentation.core.composition-test
                       'cn.li.presentation.core.runtime-composition-test])]
    (when (pos? (+ (:fail result) (:error result)))
      (System/exit 1))))

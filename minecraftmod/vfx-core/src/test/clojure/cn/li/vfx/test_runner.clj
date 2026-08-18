(ns cn.li.vfx.test-runner (:require [clojure.test :as t]
                                    [cn.li.vfx.runtime-test]
                                    [cn.li.vfx.recipe-test]))
(defn -main [& _]
  (let [result (t/run-tests 'cn.li.vfx.runtime-test
                            'cn.li.vfx.recipe-test)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))

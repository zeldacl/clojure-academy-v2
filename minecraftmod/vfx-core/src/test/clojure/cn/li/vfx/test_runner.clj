(ns cn.li.vfx.test-runner (:require [clojure.test :as t]
                                    [cn.li.vfx.replication-contract-test]
                                    [cn.li.vfx.final-engine-test]
                                    [cn.li.vfx.dependency-direction-test]))
(defn -main [& _]
  (let [result (t/run-tests 'cn.li.vfx.replication-contract-test 'cn.li.vfx.final-engine-test 'cn.li.vfx.dependency-direction-test)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))

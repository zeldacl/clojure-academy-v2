(ns cn.li.vfx.test-runner (:require [clojure.test :as t]
                                    [cn.li.vfx.runtime-test]
                                    [cn.li.vfx.recipe-test]
                                    [cn.li.vfx.vm-test]
                                    [cn.li.vfx.install-test]))
(defn -main [& _]
  (let [result (t/run-tests 'cn.li.vfx.runtime-test
                            'cn.li.vfx.recipe-test
                            'cn.li.vfx.vm-test
                            'cn.li.vfx.install-test)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))

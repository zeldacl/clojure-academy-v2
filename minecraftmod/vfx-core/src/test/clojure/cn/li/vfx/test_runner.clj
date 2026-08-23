(ns cn.li.vfx.test-runner (:require [clojure.test :as t]
                                    [cn.li.vfx.runtime-test]
                                    [cn.li.vfx.recipe-test]
                                    [cn.li.vfx.vm-test]
                                    [cn.li.vfx.install-test]
                                    [cn.li.vfx.v3-primitives-test]
                                    [cn.li.vfx.composite-loader-test]))
(defn -main [& _]
  (let [result (t/run-tests 'cn.li.vfx.runtime-test
                            'cn.li.vfx.recipe-test
                            'cn.li.vfx.vm-test
                            'cn.li.vfx.install-test
                            'cn.li.vfx.v3-primitives-test
                            'cn.li.vfx.composite-loader-test)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))

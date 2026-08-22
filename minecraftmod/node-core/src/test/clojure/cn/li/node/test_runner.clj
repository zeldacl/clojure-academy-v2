(ns cn.li.node.test-runner
  (:require [clojure.test :as t]
            [cn.li.node.descriptor-test]
            [cn.li.node.expr-test]
            [cn.li.node.value-test]
            [cn.li.node.flow-test]
            [cn.li.node.scope-test]
            [cn.li.node.composite-test]
            [cn.li.node.validate-test]
            [cn.li.node.schema-export-test]))

(defn -main [& _]
  (let [result (t/run-tests 'cn.li.node.descriptor-test
                            'cn.li.node.expr-test
                            'cn.li.node.value-test
                            'cn.li.node.flow-test
                            'cn.li.node.scope-test
                            'cn.li.node.composite-test
                            'cn.li.node.validate-test
                            'cn.li.node.schema-export-test)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))

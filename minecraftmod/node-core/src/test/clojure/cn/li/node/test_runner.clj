(ns cn.li.node.test-runner
  "Auto-discovers node-core test namespaces and runs clojure.test.
   Supports -Dnode-core.test.only=ns1,ns2 to run a subset.

   Previously a hardcoded require+run-tests list: a new *_test.clj here
   compiled fine but was silently never run unless this file was also
   edited by hand."
  (:require [cn.li.test-support.pure-auto-test-runner :as runner]))

(defn -main [& _]
  (runner/run-tests! {:root-segments ["src" "test" "clojure" "cn" "li" "node"]
                      :base-ns "cn.li.node"
                      :only-property "node-core.test.only"}))

(ns cn.li.vfx.test-runner
  "Auto-discovers vfx-core test namespaces and runs clojure.test.
   Supports -Dvfx-core.test.only=ns1,ns2 to run a subset.

   Previously a hardcoded require+run-tests list (cn.li.vfx.final-engine-test,
   dependency-direction-test only): a new *_test.clj here compiled fine but was
   silently never run unless this file was also edited by hand -- see
   node-core's identical fix for the same bug."
  (:require [cn.li.test-support.pure-auto-test-runner :as runner]))

(defn -main [& _]
  (runner/run-tests! {:root-segments ["src" "test" "clojure" "cn" "li" "vfx"]
                      :base-ns "cn.li.vfx"
                      :only-property "vfx-core.test.only"}))

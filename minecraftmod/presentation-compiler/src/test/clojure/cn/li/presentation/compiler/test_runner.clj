(ns cn.li.presentation.compiler.test-runner
  "Auto-discovers presentation-compiler test namespaces and runs
   clojure.test. Supports -Dpresentation-compiler.test.only=ns1,ns2 to run a
   subset.

   Previously a hardcoded single-namespace require+run-tests list: a new
   *_test.clj here compiled fine but was silently never run unless this file
   was also edited by hand."
  (:require [cn.li.test-support.pure-auto-test-runner :as runner]))

(defn -main [& _]
  (runner/run-tests! {:root-segments ["src" "test" "clojure" "cn" "li" "presentation" "compiler"]
                      :base-ns "cn.li.presentation.compiler"
                      :only-property "presentation-compiler.test.only"}))

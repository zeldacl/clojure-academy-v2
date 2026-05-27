(ns cn.li.forge1201.test-runner
  "Auto-discovers Forge/shared unit test namespaces and runs clojure.test.
   Supports -Dforge.test.only=ns1,ns2 to run a subset."
  (:require [cn.li.test-support.auto-test-runner :as runner])
  (:gen-class))

(defn -main [& _]
  (runner/run-tests! {:root-segments ["src" "test" "clojure" "cn" "li"]
                      :base-ns "cn.li"
                      :only-property "forge.test.only"}))

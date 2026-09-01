(ns cn.li.ability.test-runner
  "Auto-discovers ability-runtime test namespaces and runs clojure.test.
   Supports -Dability-runtime.test.only=ns1,ns2 to run a subset.

   allow-empty? is set: ability-runtime shipped with zero tests, its
   registry/combat/client-vfx boundary guarded only by a Gradle grep gate
   (verifyAbilityCompositionBoundary). P4.7 adds the first real suite here;
   until then this must not fail the build for having nothing to run."
  (:require [cn.li.test-support.auto-test-runner :as runner])
  (:gen-class))

(defn -main [& _]
  (runner/run-tests! {:root-segments ["src" "test" "clojure" "cn" "li" "ability"]
                      :base-ns "cn.li.ability"
                      :only-property "ability-runtime.test.only"
                      :allow-empty? true}))

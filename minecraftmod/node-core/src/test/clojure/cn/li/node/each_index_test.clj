(ns cn.li.node.each-index-test
  "each's optional [item index] binding form -- found converting real
   content for S6 (scatter_bomb.edn's :flow/foreach :index-as, gating
   auto-aim to only the first N-by-mastery balls fired by their position
   in the loop). index-reg already existed as compile-each's own internal
   iteration counter; this only exposes it as a second bound local.
   Compile-only proof here (node-core has zero project deps, no emitter
   to dispatch against); the real per-iteration index VALUE is proven by
   an actual dispatch in combat-core/run_test.clj -- see that
   namespace's own docstring on why compile-only proof was not enough
   for `each` once before."
  (:require [clojure.test :refer [deftest is]]
            [cn.li.node.surface :as surface]
            [cn.li.node.compile :as compile]
            [cn.li.node.ir :as ir]
            [cn.li.node.test-fixtures :as fx]))

(deftest each-with-index-binding-compiles-test
  (let [doc (surface/parse
             "{:ability :indexed :tunables {:damage {:type :double}}
               :do [(let xs (target/entities {:center ?caster/eye :radius 4.0 :limit 8}))
                    (each [t i] xs
                      (when (math/lt i 2)
                        (ac/strike t $damage)))
                    (finish {:outcome :performed})]}")]
    (is (map? (ir/validate! (compile/compile! doc fx/opts))))))

(deftest each-without-index-binding-still-compiles-test
  (let [doc (surface/parse
             "{:ability :not-indexed :tunables {:damage {:type :double}}
               :do [(let xs (target/entities {:center ?caster/eye :radius 4.0 :limit 8}))
                    (each t xs
                      (ac/strike t $damage))
                    (finish {:outcome :performed})]}")]
    (is (map? (ir/validate! (compile/compile! doc fx/opts))))))

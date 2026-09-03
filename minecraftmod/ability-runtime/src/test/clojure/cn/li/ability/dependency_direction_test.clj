(ns cn.li.ability.dependency-direction-test
  "ability-runtime must not depend on ac, bc, cc or any platform loader --
   it is the composition layer combat/vfx/presentation content modules
   consume, not the other way around. node-core/combat-core/vfx-core/
   presentation-core are all legitimate (target topology: ability-runtime
   -> mcmod, node-core, combat-core, vfx-core, presentation-core). Uses
   cn.li.node.dep-check (shared with node-core/combat-core/vfx-core), which
   reads every top-level form, not just the ns form."
  (:require [clojure.test :refer [deftest is]]
            [cn.li.node.dep-check :as dep-check]))

(def ^:private forbidden-prefixes
  ["net.minecraft" "net.minecraftforge" "net.fabricmc" "net.neoforged"
   "cn.li.platform" "cn.li.mcbase" "cn.li.mc1201" "cn.li.mc1211" "cn.li.mc262"
   "cn.li.ac" "cn.li.bc" "cn.li.cc"])

(deftest ability-runtime-source-has-no-forbidden-namespace-dependency-test
  (let [violations (dep-check/violations "src/main/clojure" forbidden-prefixes)]
    (is (empty? violations)
        (str "ability-runtime files with a forbidden namespace reference: " (vec violations)))))

(deftest sanity-scanned-more-than-a-handful-of-files-test
  (is (>= (count (dep-check/clj-files "src/main/clojure")) 5)))

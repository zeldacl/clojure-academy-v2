(ns cn.li.combat.dependency-direction-test
  "combat-core must not depend on ac, platform, or presentation-core -- see
   COMBAT_CORE.md. Uses cn.li.node.dep-check (shared with node-core and
   vfx-core), which reads every top-level form, not just the ns form: this
   module's final_compiler.clj, final_damage.clj and final_engine.clj each
   put a real dependency in a top-level (require ...) form AFTER the ns
   form, invisible to the previous ns-form-only reader here."
  (:require [clojure.test :refer [deftest is]]
            [cn.li.node.dep-check :as dep-check]))

(def ^:private forbidden-prefixes
  ["net.minecraft" "net.minecraftforge" "net.fabricmc" "net.neoforged"
   "cn.li.ac" "cn.li.platform" "cn.li.mcbase" "cn.li.mc1201" "cn.li.mc1211"
   "cn.li.mc262" "cn.li.forge" "cn.li.fabric" "cn.li.neoforge" "cn.li.presentation"])

(deftest combat-core-source-has-no-forbidden-namespace-dependency-test
  (let [violations (dep-check/violations "src/main/clojure" forbidden-prefixes)]
    (is (empty? violations)
        (str "combat-core files with a forbidden namespace reference: " (vec violations)))))

(deftest sanity-scanned-more-than-a-handful-of-files-test
  (is (>= (count (dep-check/clj-files "src/main/clojure")) 8)))

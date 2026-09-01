(ns cn.li.vfx.dependency-direction-test
  "vfx-core must not depend on ac, platform, presentation-core or combat-core
   -- see VFX_CORE.md. Uses cn.li.node.dep-check (shared with node-core and
   combat-core), which reads every top-level form, not just the ns form:
   this module's effect_schema.clj, network.clj and replication.clj each put
   a real dependency in a top-level (require ...)/(import ...) form AFTER
   the ns form, invisible to the previous ns-form-only reader here."
  (:require [clojure.test :refer [deftest is]]
            [cn.li.node.dep-check :as dep-check]))

(def ^:private forbidden-prefixes
  ["net.minecraft" "net.minecraftforge" "net.fabricmc" "net.neoforged"
   "cn.li.platform" "cn.li.mcbase" "cn.li.mc1201" "cn.li.mc1211" "cn.li.mc262"
   "cn.li.ac" "cn.li.presentation" "cn.li.combat"])

(deftest vfx-core-source-has-no-forbidden-namespace-dependency-test
  (let [violations (dep-check/violations "src/main/clojure" forbidden-prefixes)]
    (is (empty? violations)
        (str "vfx-core files with a forbidden namespace reference: " (vec violations)))))

(deftest sanity-scanned-more-than-a-handful-of-files-test
  (is (>= (count (dep-check/clj-files "src/main/clojure")) 5)))

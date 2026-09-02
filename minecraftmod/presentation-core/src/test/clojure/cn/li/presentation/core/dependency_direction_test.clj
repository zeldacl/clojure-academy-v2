(ns cn.li.presentation.core.dependency-direction-test
  "presentation-core must not depend on ac, platform, node-core, combat-core
   or vfx-core -- target topology is presentation-core -> mcmod only. Uses
   this module's own local dep-check copy (see dep_check.clj's docstring
   for why it isn't shared from node-core's test tree), which reads every
   top-level form, not just the ns form."
  (:require [clojure.test :refer [deftest is]]
            [cn.li.presentation.core.dep-check :as dep-check]))

(def ^:private forbidden-prefixes
  ["net.minecraft" "net.minecraftforge" "net.fabricmc" "net.neoforged"
   "cn.li.platform" "cn.li.mcbase" "cn.li.mc1201" "cn.li.mc1211" "cn.li.mc262"
   "cn.li.ac" "cn.li.combat" "cn.li.vfx" "cn.li.node" "cn.li.ability"])

(deftest presentation-core-source-has-no-forbidden-namespace-dependency-test
  (let [violations (dep-check/violations "src/main/clojure" forbidden-prefixes)]
    (is (empty? violations)
        (str "presentation-core files with a forbidden namespace reference: " (vec violations)))))

(deftest sanity-scanned-more-than-a-handful-of-files-test
  (is (>= (count (dep-check/clj-files "src/main/clojure")) 5)))

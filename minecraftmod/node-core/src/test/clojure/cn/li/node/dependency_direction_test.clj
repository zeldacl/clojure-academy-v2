(ns cn.li.node.dependency-direction-test
  "node-core must not depend on Minecraft, mcmod, or either domain module --
   see NODE_LANGUAGE.md section 2.1. Uses cn.li.node.dep-check, which reads
   every top-level form (not just the ns form), fixing a real gap: six main-
   source files across combat-core and vfx-core put a real dependency in a
   top-level (require ...) form AFTER the ns form, invisible to the previous
   ns-form-only reader here."
  (:require [clojure.test :refer [deftest is]]
            [cn.li.node.dep-check :as dep-check]))

(def ^:private forbidden-prefixes
  ["net.minecraft" "net.minecraftforge" "net.fabricmc" "net.neoforged"
   "cn.li.mcmod" "cn.li.ac" "cn.li.platform" "cn.li.mcbase" "cn.li.mc1201"
   "cn.li.mc1211" "cn.li.mc262" "cn.li.forge" "cn.li.fabric" "cn.li.neoforge"
   "cn.li.presentation" "cn.li.combat" "cn.li.vfx"])

(deftest node-core-source-has-no-forbidden-namespace-dependency-test
  (let [violations (dep-check/violations "src/main/clojure" forbidden-prefixes)]
    (is (empty? violations)
        (str "node-core files with a forbidden namespace reference: " (vec violations)))))

(deftest sanity-scanned-more-than-a-handful-of-files-test
  (is (>= (count (dep-check/clj-files "src/main/clojure")) 8)))

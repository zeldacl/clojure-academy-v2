(ns cn.li.ac.ability.service.combat-runtime-tunables-test
  "Coverage for the schema v2 :tunables pipeline (design B): config-driven
   materialization at catalog load (skill_config/overlay-edn-tunables) and
   curve application against live skill-exp at activation
   (combat_runtime/materialize-tunables). No shipped ability declares
   :tunables yet (schema-version 1), so this exercises the mechanism
   directly rather than through a real dispatch."
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.ability.service.combat-runtime :as combat-runtime]))

(deftest materialize-tunables-applies-mastery-lerp-against-live-skill-exp
  (testing "a :mastery-lerp tunable interpolates (lo,hi) using the owner's current skill-exp, not a config-load-time snapshot"
    (let [ability {:tunables {:damage {:curve :mastery-lerp :range [10.0 20.0]}}}]
      (is (= 10.0 (get (#'combat-runtime/materialize-tunables ability 0.0) :damage)))
      (is (= 15.0 (get (#'combat-runtime/materialize-tunables ability 0.5) :damage)))
      (is (= 20.0 (get (#'combat-runtime/materialize-tunables ability 1.0) :damage))))))

(deftest materialize-tunables-clamps-mastery-lerp-t
  (testing "skill-exp outside [0,1] does not extrapolate past the declared range"
    (let [ability {:tunables {:damage {:curve :mastery-lerp :range [10.0 20.0]}}}]
      (is (= 10.0 (get (#'combat-runtime/materialize-tunables ability -5.0) :damage)))
      (is (= 20.0 (get (#'combat-runtime/materialize-tunables ability 5.0) :damage))))))

(deftest materialize-tunables-applies-affine-curve
  (testing "an :affine tunable is base + slope*skill-exp, distinct from :mastery-lerp's bounded interpolation"
    (let [ability {:tunables {:exp-entity {:curve :affine :range [0.01 0.02]}}}]
      (is (= 0.01 (get (#'combat-runtime/materialize-tunables ability 0.0) :exp-entity)))
      (is (= 0.03 (get (#'combat-runtime/materialize-tunables ability 1.0) :exp-entity))))))

(deftest materialize-tunables-passes-const-through-unchanged
  (testing "a :const tunable ignores skill-exp entirely"
    (let [ability {:tunables {:fishing-probability {:curve :const :value 0.35}}}]
      (is (= 0.35 (get (#'combat-runtime/materialize-tunables ability 0.9) :fishing-probability))))))

(deftest materialize-tunables-is-a-noop-for-abilities-without-tunables
  (testing "schema-version-1 abilities (no :tunables key) produce an empty map, not an error"
    (is (= {} (#'combat-runtime/materialize-tunables {:id :railgun} 0.5)))))

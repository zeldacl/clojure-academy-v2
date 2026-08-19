(ns cn.li.ac.ability.skill-config-tunables-test
  "Coverage for the catalog-load half of the schema v2 :tunables pipeline
   (design B): cn.li.ac.ability.skill-config/overlay-edn-tunables. Separate
   file from skill_config_test.clj, which currently fails to compile for
   unrelated reasons (a stale ability-content var it references)."
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.ability.skill-config :as skill-config]))

(deftest overlay-edn-tunables-is-a-noop-without-a-tunables-block
  (testing "schema-version-1 abilities (no :tunables key) pass through unchanged"
    (let [ability {:id :railgun :parameters {}}]
      (is (= ability (skill-config/overlay-edn-tunables ability))))))

(deftest overlay-edn-tunables-materializes-const-and-lerp-curves
  (testing "convention over configuration: a tunable named exactly :beam.damage reads the
            existing railgun field of that id with no binding-table entry required"
    (let [ability (skill-config/overlay-edn-tunables
                   {:id :railgun
                    :tunables {:beam.damage {:curve :mastery-lerp}}})
          materialized (get-in ability [:tunables :beam.damage])]
      (is (contains? materialized :range))
      (is (= 2 (count (:range materialized))))
      (is (every? number? (:range materialized))))))

(deftest overlay-edn-tunables-honors-binding-override-when-names-differ
  (testing "edn-tunable-bindings lets a tunable name diverge from its field-id when convention doesn't fit"
    (with-redefs [skill-config/edn-tunable-bindings {:railgun {:damage :beam.damage}}]
      (let [ability (skill-config/overlay-edn-tunables
                     {:id :railgun
                      :tunables {:damage {:curve :mastery-lerp}}})
            materialized (get-in ability [:tunables :damage])]
        (is (contains? materialized :range))
        (is (= 2 (count (:range materialized))))))))

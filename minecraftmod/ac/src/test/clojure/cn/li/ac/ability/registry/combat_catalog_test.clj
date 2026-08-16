(ns cn.li.ac.ability.registry.combat-catalog-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.registry.combat-catalog :as catalog]))

(use-fixtures :each
  (fn [f]
    (catalog/reset-for-test!)
    (f)
    (catalog/reset-for-test!)))

(deftest metadata-registry-does-not-require-legacy-actions
  (is (true? (catalog/register-skills!
              [{:id :arc-gen :category-id :electromaster :level 1
                :controllable? true :ctrl-id :arc-gen :pattern :instant
                :name-key "ability.skill.electromaster.arc-gen"
                :description-key "ability.skill.electromaster.arc-gen.desc"
                :icon "textures/abilities/electromaster/skills/arc-gen.png"
                :execution :combat-core
                :combat-ability-id :arc-gen}])))
  (is (= :combat-core (:execution (catalog/get-skill :arc-gen))))
  (is (catalog/installed?))
  (is (= [:arc-gen]
         (mapv first (catalog/raw-skill-entries)))))

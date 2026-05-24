(ns cn.li.ac.ability.datagen.registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.ability.datagen.registry :as ability-datagen]
            [cn.li.ac.content.ability :as ability-content]
            [cn.li.mcmod.datagen.metadata :as metadata]))

(deftest register-datagen-metadata-populates-ability-translations
       (cn.li.ac.ability.item-actions/reset-item-action-registries!)
  (ability-content/init-ability-content!)
  (reset! metadata/translations {:en_us {} :zh_cn {}})

  (ability-datagen/register-datagen-metadata!)

  (testing "ability skill translation keys are exported"
    (is (= "Flesh Ripping"
           (get-in @metadata/translations [:en_us "ability.skill.teleporter.flesh_ripping"])))
    (is (= "Ability skill: Flesh Ripping"
           (get-in @metadata/translations [:en_us "ability.skill.teleporter.flesh_ripping.desc"]))))

  (testing "zh_cn fallback receives the same generated keys"
    (is (= "Flesh Ripping"
           (get-in @metadata/translations [:zh_cn "ability.skill.teleporter.flesh_ripping"])))
    (is (= "Ability skill: Flesh Ripping"
           (get-in @metadata/translations [:zh_cn "ability.skill.teleporter.flesh_ripping.desc"])))))

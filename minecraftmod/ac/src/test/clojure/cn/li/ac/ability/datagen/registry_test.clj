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
           (get-in @metadata/translations [:en_us "ability.skill.teleporter.flesh_ripping.desc"])))
    (is (= "Dimension Folding Theorem"
           (get-in @metadata/translations [:en_us "ability.skill.teleporter.dim_folding_theorem"])))
    (is (= "Exploit weak spatial folding during teleportation attacks to split space unpredictably and amplify the final hit."
           (get-in @metadata/translations [:en_us "ability.skill.teleporter.dim_folding_theorem.desc"])))
    (is (= "Critical Hit %s"
           (get-in @metadata/translations [:en_us "ability.teleporter.critical_hit"]))))

  (testing "zh_cn receives teleporter-specific overrides"
    (is (= "Flesh Ripping"
           (get-in @metadata/translations [:zh_cn "ability.skill.teleporter.flesh_ripping"])))
    (is (= "折叠空间概论"
           (get-in @metadata/translations [:zh_cn "ability.skill.teleporter.dim_folding_theorem"])))
    (is (= "利用传送攻击中不稳定的微弱空间折叠现象，让空间产生概率性的分裂，从而进一步提高伤害。"
           (get-in @metadata/translations [:zh_cn "ability.skill.teleporter.dim_folding_theorem.desc"])))
    (is (= "暴击 %s"
           (get-in @metadata/translations [:zh_cn "ability.teleporter.critical_hit"])))))

(ns cn.li.ac.content.ability.teleporter.dim-folding-theorem
  "Teleporter passive placeholder. Crit logic is implemented in tp-skill-helper."
  (:require [cn.li.ac.ability.dsl :refer [defskill!]]))

(defskill! dim-folding-theorem
  :id :dim-folding-theorem
  :category-id :teleporter
  :name-key "ability.skill.teleporter.dim_folding_theorem"
  :description-key "ability.skill.teleporter.dim_folding_theorem.desc"
  :icon "textures/abilities/teleporter/skills/dim_folding_theorem.png"
  :ui-position [50 75]
  :level 1
  :controllable? false
  :ctrl-id :dim-folding-theorem
  :pattern :passive
  :prerequisites [{:skill-id :threatening-teleport :min-exp 0.2}])

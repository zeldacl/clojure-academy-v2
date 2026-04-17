(ns cn.li.ac.content.ability.electromaster.arc-gen
  (:require [cn.li.ac.ability.dsl :refer [defskill!]]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]))

(defskill! arc-gen
  :id :arc-gen
  :category-id :electromaster
  :name-key "ability.skill.electromaster.arc_gen"
  :description-key "ability.skill.electromaster.arc_gen.desc"
  :icon "textures/abilities/electromaster/skills/arc_gen.png"
  :ui-position [24 46]
  :level 1
  :controllable? true
  :ctrl-id :arc-gen
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :cooldown-ticks 14
  :pattern :hold-channel
  :cost {:tick {:mode :runtime-speed
                :cp-speed 0.8
                :overload-speed 0.7}}
  :actions {:tick! (fn [{:keys [player-id]}]
                     (skill-effects/add-skill-exp! player-id :arc-gen 0.003))
            :up!   (fn [{:keys [player-id]}]
                     (skill-effects/add-skill-exp! player-id :arc-gen 0.015))})

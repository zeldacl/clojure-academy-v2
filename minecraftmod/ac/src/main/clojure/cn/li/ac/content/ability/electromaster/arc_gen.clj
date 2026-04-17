(ns cn.li.ac.content.ability.electromaster.arc-gen
  (:require [cn.li.ac.ability.dsl :refer [defskill!]]
            [cn.li.ac.content.ability.common :as ability-common]))

(defn arc-gen-on-key-up
  [{:keys [player-id]}]
  (ability-common/add-skill-exp! player-id :arc-gen 0.015))

(defn arc-gen-on-key-tick
  [{:keys [player-id]}]
  (ability-common/add-skill-exp! player-id :arc-gen 0.003))

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
  :actions {:tick! arc-gen-on-key-tick
            :up! arc-gen-on-key-up})

(ns cn.li.ac.content.ability.telekinesis.vec-manip
  (:require [cn.li.ac.ability.dsl :refer [defskill!]]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]))

(defskill! vec-manip
  :id :vec-manip
  :category-id :vecmanip
  :name-key "ability.skill.telekinesis.vec_manip"
  :description-key "ability.skill.telekinesis.vec_manip.desc"
  :icon "textures/abilities/electromaster/skills/mag_manip.png"
  :level 1
  :controllable? true
  :ctrl-id :vec-manip
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :cooldown-ticks 18
  :pattern :hold-channel
  :cost {:tick {:mode :runtime-speed
                :cp-speed 0.9
                :overload-speed 0.8}}
  :actions {:tick! (fn [{:keys [player-id]}]
                     (skill-effects/add-skill-exp! player-id :vec-manip 0.0025))
            :up!   (fn [{:keys [player-id]}]
                     (skill-effects/add-skill-exp! player-id :vec-manip 0.01))})

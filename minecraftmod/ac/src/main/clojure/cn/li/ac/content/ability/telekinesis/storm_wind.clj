(ns cn.li.ac.content.ability.telekinesis.storm-wind
  (:require [cn.li.ac.ability.dsl :refer [defskill!]]))

(defskill! storm-wind
  :id :storm-wind
  :category-id :telekinesis
  :name-key "ability.skill.telekinesis.storm_wind"
  :description-key "ability.skill.telekinesis.storm_wind.desc"
  :icon "textures/abilities/vecmanip/skills/storm_wing.png"
  :level 2
  :controllable? true
  :ctrl-id :storm-wind
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :cooldown-ticks 24
  :pattern :hold-channel
  :cost {:tick {:mode :runtime-speed
                :cp-speed 1.2
                :overload-speed 1.1}}
  :prerequisites [{:skill-id :vec-manip :min-exp 0.4}])

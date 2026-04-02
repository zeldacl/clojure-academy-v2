(ns cn.li.ac.content.ability
  "This namespace is pure content data loaded by ac core startup."
  (:require [cn.li.ac.ability.dsl :refer [defcategory defskill]]))

;; Categories
(defcategory electromaster
  :id :electromaster
  :name-key "ability.category.electromaster"
  :icon "textures/ability/category/electromaster.png"
  :color [0.27 0.69 1.0 1.0]
  :prog-incr-rate 1.0
  :enabled true)

(defcategory telekinesis
  :id :telekinesis
  :name-key "ability.category.telekinesis"
  :icon "textures/ability/category/telekinesis.png"
  :color [0.92 0.73 0.27 1.0]
  :prog-incr-rate 1.0
  :enabled true)

;; Electromaster skills
(defskill arc-gen
  :id :arc-gen
  :category-id :electromaster
  :name-key "ability.skill.electromaster.arc_gen"
  :description-key "ability.skill.electromaster.arc_gen.desc"
  :icon "textures/ability/skill/arc_gen.png"
  :level 1
  :controllable? true
  :ctrl-id :arc-gen)

(defskill railgun
  :id :railgun
  :category-id :electromaster
  :name-key "ability.skill.electromaster.railgun"
  :description-key "ability.skill.electromaster.railgun.desc"
  :icon "textures/ability/skill/railgun.png"
  :level 3
  :controllable? true
  :ctrl-id :railgun
  :prerequisites [{:skill-id :arc-gen :min-exp 0.6}])

;; Telekinesis skills
(defskill vec-manip
  :id :vec-manip
  :category-id :telekinesis
  :name-key "ability.skill.telekinesis.vec_manip"
  :description-key "ability.skill.telekinesis.vec_manip.desc"
  :icon "textures/ability/skill/vec_manip.png"
  :level 1
  :controllable? true
  :ctrl-id :vec-manip)

(defskill storm-wind
  :id :storm-wind
  :category-id :telekinesis
  :name-key "ability.skill.telekinesis.storm_wind"
  :description-key "ability.skill.telekinesis.storm_wind.desc"
  :icon "textures/ability/skill/storm_wind.png"
  :level 2
  :controllable? true
  :ctrl-id :storm-wind
  :prerequisites [{:skill-id :vec-manip :min-exp 0.4}])

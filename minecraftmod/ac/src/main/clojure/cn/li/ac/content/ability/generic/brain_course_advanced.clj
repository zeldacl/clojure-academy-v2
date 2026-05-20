(ns cn.li.ac.content.ability.generic.brain-course-advanced
  "Generic passive skill: Advanced Brain Course (+1500 CP, +100 overload)."
  (:require [cn.li.ac.ability.passive :as passive]
            [cn.li.ac.ability.registry.event :as evt]))

(def ^:private categories [:electromaster :meltdowner :teleporter :vecmanip])

(defn- skill-id
  [cat-id]
  (keyword (name cat-id) "brain-course-advanced"))

(defn- brain-course-id
  [cat-id]
  (keyword (name cat-id) "brain-course"))

(defn- register-passive-hooks!
  []
  (doseq [cat-id categories
          :let [sid (skill-id cat-id)]]
    (passive/register-passive-calc-handler!
      (keyword (name cat-id) "brain-course-advanced/max-cp")
      evt/CALC-MAX-CP
      sid
      (fn [value _event] (+ value 1500.0)))
    (passive/register-passive-calc-handler!
      (keyword (name cat-id) "brain-course-advanced/max-overload")
      evt/CALC-MAX-OVERLOAD
      sid
      (fn [value _event] (+ value 100.0)))))

(def skill-specs
  (mapv (fn [cat-id]
          (let [sid (skill-id cat-id)]
            {:id sid
             :category-id cat-id
             :name-key "ability.skill.generic.brain_course_advanced"
             :description-key "ability.skill.generic.brain_course_advanced.desc"
             :icon "textures/abilities/generic/skills/brain_course_advanced.png"
             :ui-position [115 110]
             :level 4
             :controllable? false
             :ctrl-id :brain-course-advanced
             :pattern :passive
             :prerequisites [{:skill-id (brain-course-id cat-id) :min-exp 0.0}]
             :conditions [{:type :any-skill-level :level 4}]
               :ac/content-type :skill}))
        categories))

(defn init!
  []
  (register-passive-hooks!))

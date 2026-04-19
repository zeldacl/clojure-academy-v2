(ns cn.li.ac.content.ability.generic.brain-course
  "Generic passive skill: Brain Course (+1000 max CP)."
  (:require [cn.li.ac.ability.passive :as passive]
            [cn.li.ac.ability.registry.event :as evt]
            [cn.li.ac.ability.registry.skill :as sk]))

(def ^:private categories [:electromaster :meltdowner :teleporter :vecmanip])

(defn- skill-id
  [cat-id]
  (keyword (name cat-id) "brain-course"))

(defn- register-passive-hooks!
  []
  (doseq [cat-id categories
          :let [sid (skill-id cat-id)]]
    (passive/register-passive-calc-handler!
      (keyword (name cat-id) "brain-course/max-cp")
      evt/CALC-MAX-CP
      sid
      (fn [value _event] (+ value 1000.0)))))

(doseq [cat-id categories
        :let [sid (skill-id cat-id)]]
  (sk/register-skill!
    {:id sid
     :category-id cat-id
     :name-key "ability.skill.generic.brain_course"
     :description-key "ability.skill.generic.brain_course.desc"
     :icon "textures/abilities/generic/skills/brain_course.png"
     :ui-position [30 110]
     :level 3
     :controllable? false
     :ctrl-id :brain-course
     :pattern :passive
     :conditions [{:type :any-skill-level :level 3}]}))

(register-passive-hooks!)

(ns cn.li.ac.content.ability.generic.mind-course
  "Generic passive skill: Mind Course (x1.2 CP recovery)."
  (:require [cn.li.ac.ability.passive :as passive]
            [cn.li.ac.ability.registry.event :as evt]))

(def ^:private categories [:electromaster :meltdowner :teleporter :vecmanip])

(defn- skill-id
  [cat-id]
  (keyword (name cat-id) "mind-course"))

(defn- advanced-id
  [cat-id]
  (keyword (name cat-id) "brain-course-advanced"))

(defn- register-passive-hooks!
  []
  (doseq [cat-id categories
          :let [sid (skill-id cat-id)]]
    (passive/register-passive-calc-handler!
      (keyword (name cat-id) "mind-course/cp-recovery")
      evt/CALC-CP-RECOVER-SPEED
      sid
      (fn [value _event] (* value 1.2)))))

(def skill-specs
  (mapv (fn [cat-id]
          (let [sid (skill-id cat-id)]
            {:id sid
             :category-id cat-id
             :name-key "ability.skill.generic.mind_course"
             :description-key "ability.skill.generic.mind_course.desc"
             :icon "textures/abilities/generic/skills/mind_course.png"
             :ui-position [205 110]
             :level 5
             :controllable? false
             :ctrl-id :mind-course
             :pattern :passive
             :prerequisites [{:skill-id (advanced-id cat-id) :min-exp 0.0}]
             :conditions [{:type :any-skill-level :level 5}]
               :ac/content-type :skill}))
        categories))

(defn init!
  []
  (register-passive-hooks!))

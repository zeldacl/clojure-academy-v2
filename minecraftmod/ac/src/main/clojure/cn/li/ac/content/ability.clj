(ns cn.li.ac.content.ability
  "This namespace is pure content data loaded by ac core startup."
  (:require [cn.li.ac.ability.dsl :refer [defcategory defskill]]
            [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.service.learning :as learning]
            [cn.li.ac.ability.skill :as skill]
            [cn.li.ac.ability.event :as ability-evt]))

(defn- add-skill-exp!
  [player-id skill-id amount]
  (when-let [state (ps/get-player-state player-id)]
    (let [ability-data (:ability-data state)
          spec (skill/get-skill skill-id)
          exp-rate (double (or (:exp-incr-speed spec) 1.0))
          {:keys [data events]} (learning/add-skill-exp ability-data
                                                        player-id
                                                        skill-id
                                                        amount
                                                        exp-rate)]
      (ps/update-ability-data! player-id (constantly data))
      (doseq [e events]
        (ability-evt/fire-ability-event! e)))))

(defn- arc-gen-on-key-up
  [{:keys [player-id]}]
  (add-skill-exp! player-id :arc-gen 0.015))

(defn- arc-gen-on-key-tick
  [{:keys [player-id]}]
  (add-skill-exp! player-id :arc-gen 0.003))

(defn- railgun-on-key-tick
  [{:keys [player-id]}]
  (add-skill-exp! player-id :railgun 0.004))

(defn- railgun-on-key-up
  [{:keys [player-id]}]
  (add-skill-exp! player-id :railgun 0.02))

(defn- vec-manip-on-key-tick
  [{:keys [player-id]}]
  (add-skill-exp! player-id :vec-manip 0.0025))

(defn- vec-manip-on-key-up
  [{:keys [player-id]}]
  (add-skill-exp! player-id :vec-manip 0.01))

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
  :ctrl-id :arc-gen
  :cp-consume-speed 0.8
  :overload-consume-speed 0.7
  :cooldown-ticks 14
  :on-key-tick arc-gen-on-key-tick
  :on-key-up arc-gen-on-key-up)

(defskill railgun
  :id :railgun
  :category-id :electromaster
  :name-key "ability.skill.electromaster.railgun"
  :description-key "ability.skill.electromaster.railgun.desc"
  :icon "textures/ability/skill/railgun.png"
  :level 3
  :controllable? true
  :ctrl-id :railgun
  :cp-consume-speed 1.6
  :overload-consume-speed 1.4
  :cooldown-ticks 34
  :on-key-tick railgun-on-key-tick
  :on-key-up railgun-on-key-up
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
  :ctrl-id :vec-manip
  :cp-consume-speed 0.9
  :overload-consume-speed 0.8
  :cooldown-ticks 18
  :on-key-tick vec-manip-on-key-tick
  :on-key-up vec-manip-on-key-up)

(defskill storm-wind
  :id :storm-wind
  :category-id :telekinesis
  :name-key "ability.skill.telekinesis.storm_wind"
  :description-key "ability.skill.telekinesis.storm_wind.desc"
  :icon "textures/ability/skill/storm_wind.png"
  :level 2
  :controllable? true
  :ctrl-id :storm-wind
  :cp-consume-speed 1.2
  :overload-consume-speed 1.1
  :cooldown-ticks 24
  :prerequisites [{:skill-id :vec-manip :min-exp 0.4}])

(ns cn.li.ac.content.ability
  "This namespace is pure content data loaded by ac core startup."
  (:require [cn.li.ac.ability.dsl :refer [defcategory defskill]]
            [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.service.learning :as learning]
            [cn.li.ac.ability.skill :as skill]
            [cn.li.ac.ability.event :as ability-evt]
            [cn.li.ac.content.ability.electromaster.thunder-bolt :as thunder-bolt]
            [cn.li.ac.content.ability.electromaster.thunder-clap :as thunder-clap]
            [cn.li.ac.content.ability.electromaster.body-intensify :as body-intensify]
            [cn.li.ac.content.ability.electromaster.current-charging :as current-charging]
            [cn.li.ac.content.ability.electromaster.mag-movement :as mag-movement]
            [cn.li.ac.content.ability.electromaster.mag-manip :as mag-manip]
            [cn.li.ac.content.ability.electromaster.railgun :as railgun]
            [cn.li.ac.content.ability.meltdowner.meltdowner :as meltdowner]
            [cn.li.ac.content.ability.teleporter.mark-teleport :as mark-teleport]
            [cn.li.ac.content.ability.teleporter.location-teleport :as location-teleport]
            [cn.li.ac.content.ability.vecmanip.directed-shock :as directed-shock]
            [cn.li.ac.content.ability.vecmanip.groundshock :as groundshock]
            [cn.li.ac.content.ability.vecmanip.vec-accel :as vec-accel]
            [cn.li.ac.content.ability.vecmanip.vec-deviation :as vec-deviation]
            [cn.li.ac.content.ability.vecmanip.vec-reflection :as vec-reflection]
            [cn.li.ac.content.ability.vecmanip.directed-blastwave :as directed-blastwave]
            [cn.li.ac.content.ability.vecmanip.storm-wing :as storm-wing]
            [cn.li.ac.content.ability.vecmanip.blood-retrograde :as blood-retrograde]
            [cn.li.ac.content.ability.vecmanip.plasma-cannon :as plasma-cannon]
            [cn.li.ac.ability.category :as category]
            [cn.li.mcmod.util.log :as log]))

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
  :icon "textures/abilities/electromaster/icon.png"
  :color [0.27 0.69 1.0 1.0]
  :prog-incr-rate 1.0
  :enabled true)

(defcategory telekinesis
  :id :telekinesis
  :name-key "ability.category.telekinesis"
  :icon "textures/abilities/generic/skills/mind_course.png"
  :color [0.92 0.73 0.27 1.0]
  :prog-incr-rate 1.0
  :enabled true)

(defcategory meltdowner
  :id :meltdowner
  :name-key "ability.category.meltdowner"
  :icon "textures/abilities/meltdowner/icon.png"
  :color [0.1 1.0 0.3 1.0]
  :prog-incr-rate 1.0
  :enabled true)

(defcategory teleporter
  :id :teleporter
  :name-key "ability.category.teleporter"
  :icon "textures/abilities/teleporter/icon.png"
  :color [1.0 1.0 1.0 1.0]
  :prog-incr-rate 1.0
  :enabled true)

(defcategory vecmanip
  :id :vecmanip
  :name-key "ability.category.vecmanip"
  :icon "textures/abilities/vecmanip/icon.png"
  :color [0.0 0.0 0.0 1.0]
  :prog-incr-rate 1.0
  :enabled true)

;; Electromaster skills
(defskill arc-gen
  :id :arc-gen
  :category-id :electromaster
  :name-key "ability.skill.electromaster.arc_gen"
  :description-key "ability.skill.electromaster.arc_gen.desc"
  :icon "textures/abilities/electromaster/skills/arc_gen.png"
  :ui-position [24 46]
  :level 1
  :controllable? true
  :ctrl-id :arc-gen
  :cp-consume-speed 0.8
  :overload-consume-speed 0.7
  :cooldown-ticks 14
  :on-key-tick arc-gen-on-key-tick
  :on-key-up arc-gen-on-key-up)

(defskill thunder-bolt
  :id :thunder-bolt
  :category-id :electromaster
  :name-key "ability.skill.electromaster.thunder_bolt"
  :description-key "ability.skill.electromaster.thunder_bolt.desc"
  :icon "textures/abilities/electromaster/skills/thunder_bolt.png"
  :ui-position [86 67]
  :level 2
  :controllable? false
  :ctrl-id :thunder-bolt
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :cooldown-ticks 100
  :on-key-down thunder-bolt/thunder-bolt-on-key-down
  :on-key-tick thunder-bolt/thunder-bolt-on-key-tick
  :on-key-up thunder-bolt/thunder-bolt-on-key-up
  :on-key-abort thunder-bolt/thunder-bolt-on-key-abort
  :prerequisites [{:skill-id :arc-gen :min-exp 1.0}
                  {:skill-id :current-charging :min-exp 0.7}])

(defskill thunder-clap
  :id :thunder-clap
  :category-id :electromaster
  :name-key "ability.skill.electromaster.thunder_clap"
  :description-key "ability.skill.electromaster.thunder_clap.desc"
  :icon "textures/abilities/electromaster/skills/thunder_clap.png"
  :ui-position [204 80]
  :level 1
  :controllable? true
  :ctrl-id :thunder-clap
  :cp-consume-speed 1.0
  :overload-consume-speed 0.9
  :cooldown-ticks 150
  :on-key-down thunder-clap/thunder-clap-on-key-down
  :on-key-tick thunder-clap/thunder-clap-on-key-tick
  :on-key-up thunder-clap/thunder-clap-on-key-up
  :on-key-abort thunder-clap/thunder-clap-on-key-abort
  :prerequisites [{:skill-id :thunder-bolt :min-exp 1.0}])

(defskill current-charging
  :id :current-charging
  :category-id :electromaster
  :name-key "ability.skill.electromaster.current_charging"
  :description-key "ability.skill.electromaster.current_charging.desc"
  :icon "textures/abilities/electromaster/skills/charging.png"
  :ui-position [55 18]
  :level 2
  :controllable? true
  :ctrl-id :current-charging
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :cooldown-ticks 40
  :on-key-down current-charging/current-charging-on-key-down
  :on-key-tick current-charging/current-charging-on-key-tick
  :on-key-up current-charging/current-charging-on-key-up
  :on-key-abort current-charging/current-charging-on-key-abort
  :prerequisites [{:skill-id :arc-gen :min-exp 0.3}])

(defskill body-intensify
  :id :body-intensify
  :category-id :electromaster
  :name-key "ability.skill.electromaster.body_intensify"
  :description-key "ability.skill.electromaster.body_intensify.desc"
  :icon "textures/abilities/electromaster/skills/body_intensify.png"
  :ui-position [97 15]
  :level 4
  :controllable? true
  :ctrl-id :body-intensify
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :cooldown-ticks 750
  :on-key-down body-intensify/body-intensify-on-key-down
  :on-key-tick body-intensify/body-intensify-on-key-tick
  :on-key-up body-intensify/body-intensify-on-key-up
  :on-key-abort body-intensify/body-intensify-on-key-abort
  :prerequisites [{:skill-id :arc-gen :min-exp 1.0}
                  {:skill-id :current-charging :min-exp 1.0}])

(defskill mag-movement
  :id :mag-movement
  :category-id :electromaster
  :name-key "ability.skill.electromaster.mag_movement"
  :description-key "ability.skill.electromaster.mag_movement.desc"
  :icon "textures/abilities/electromaster/skills/mag_movement.png"
  :ui-position [137 35]
  :level 3
  :controllable? true
  :ctrl-id :mag-movement
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :cooldown-ticks 60
  :on-key-down mag-movement/mag-movement-on-key-down
  :on-key-tick mag-movement/mag-movement-on-key-tick
  :on-key-up mag-movement/mag-movement-on-key-up
  :on-key-abort mag-movement/mag-movement-on-key-abort
  :prerequisites [{:skill-id :arc-gen :min-exp 1.0}
                  {:skill-id :current-charging :min-exp 0.7}])

(defskill mag-manip
  :id :mag-manip
  :category-id :electromaster
  :name-key "ability.skill.electromaster.mag_manip"
  :description-key "ability.skill.electromaster.mag_manip.desc"
  :icon "textures/abilities/electromaster/skills/mag_manip.png"
  :ui-position [204 33]
  :level 3
  :controllable? true
  :ctrl-id :mag-manip
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :cooldown-ticks 60
  :on-key-down mag-manip/mag-manip-on-key-down
  :on-key-tick mag-manip/mag-manip-on-key-tick
  :on-key-up mag-manip/mag-manip-on-key-up
  :on-key-abort mag-manip/mag-manip-on-key-abort
  :prerequisites [{:skill-id :mag-movement :min-exp 0.5}])

(defskill railgun
  :id :railgun
  :category-id :electromaster
  :name-key "ability.skill.electromaster.railgun"
  :description-key "ability.skill.electromaster.railgun.desc"
  :icon "textures/abilities/electromaster/skills/railgun.png"
  :ui-position [164 59]
  :level 3
  :controllable? true
  :ctrl-id :railgun
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :cooldown-ticks 1
  :on-key-down railgun/railgun-on-key-down
  :on-key-tick railgun/railgun-on-key-tick
  :on-key-up railgun/railgun-on-key-up
  :on-key-abort railgun/railgun-on-key-abort
  :prerequisites [{:skill-id :thunder-bolt :min-exp 0.3}
                  {:skill-id :mag-manip :min-exp 1.0}])

;; Telekinesis skills
(defskill vec-manip
  :id :vec-manip
  :category-id :telekinesis
  :name-key "ability.skill.telekinesis.vec_manip"
  :description-key "ability.skill.telekinesis.vec_manip.desc"
  :icon "textures/abilities/electromaster/skills/mag_manip.png"
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
  :icon "textures/abilities/vecmanip/skills/storm_wing.png"
  :level 2
  :controllable? true
  :ctrl-id :storm-wind
  :cp-consume-speed 1.2
  :overload-consume-speed 1.1
  :cooldown-ticks 24
  :prerequisites [{:skill-id :vec-manip :min-exp 0.4}])

;; Meltdowner skills
(defskill meltdowner
  :id :meltdowner
  :category-id :meltdowner
  :name-key "ability.skill.meltdowner.meltdowner"
  :description-key "ability.skill.meltdowner.meltdowner.desc"
  :icon "textures/abilities/meltdowner/skills/meltdowner.png"
  :level 1
  :controllable? true
  :ctrl-id :meltdowner
  :cp-consume-speed 1.3
  :overload-consume-speed 1.2
  :cooldown-ticks 200
  :on-key-down meltdowner/meltdowner-on-key-down
  :on-key-tick meltdowner/meltdowner-on-key-tick
  :on-key-up meltdowner/meltdowner-on-key-up
  :on-key-abort meltdowner/meltdowner-on-key-abort)

;; Teleporter skills
(defskill mark-teleport
  :id :mark-teleport
  :category-id :teleporter
  :name-key "ability.skill.teleporter.mark_teleport"
  :description-key "ability.skill.teleporter.mark_teleport.desc"
  :icon "textures/abilities/teleporter/skills/mark_teleport.png"
  :level 1
  :controllable? true
  :ctrl-id :mark-teleport
  :cp-consume-speed 0.8
  :overload-consume-speed 0.5
  :cooldown-ticks 20
  :on-key-down mark-teleport/mark-teleport-on-key-down
  :on-key-tick mark-teleport/mark-teleport-on-key-tick
  :on-key-up mark-teleport/mark-teleport-on-key-up
  :on-key-abort mark-teleport/mark-teleport-on-key-abort)

(defskill location-teleport
  :id :location-teleport
  :category-id :teleporter
  :name-key "ability.skill.teleporter.location_teleport"
  :description-key "ability.skill.teleporter.location_teleport.desc"
  :icon "textures/abilities/teleporter/skills/location_teleport.png"
  :level 2
  :controllable? false
  :ctrl-id :location-teleport
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :cooldown-ticks 25
  :on-key-down location-teleport/location-teleport-on-key-down
  :on-key-tick location-teleport/location-teleport-on-key-tick
  :on-key-up location-teleport/location-teleport-on-key-up
  :on-key-abort location-teleport/location-teleport-on-key-abort
  :prerequisites [{:skill-id :mark-teleport :min-exp 0.5}])

;; Vector Manipulation skills
(defskill directed-shock
  :id :directed-shock
  :category-id :vecmanip
  :name-key "ability.skill.vecmanip.directed_shock"
  :description-key "ability.skill.vecmanip.directed_shock.desc"
  :icon "textures/abilities/vecmanip/skills/dir_shock.png"
  :level 1
  :controllable? false
  :ctrl-id :directed-shock
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :cooldown-ticks 60
  :on-key-down directed-shock/directed-shock-on-key-down
  :on-key-tick directed-shock/directed-shock-on-key-tick
  :on-key-up directed-shock/directed-shock-on-key-up
  :on-key-abort directed-shock/directed-shock-on-key-abort)

(defskill groundshock
  :id :groundshock
  :category-id :vecmanip
  :name-key "ability.skill.vecmanip.groundshock"
  :description-key "ability.skill.vecmanip.groundshock.desc"
  :icon "textures/abilities/vecmanip/skills/ground_shock.png"
  :level 1
  :controllable? false
  :ctrl-id :groundshock
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :cooldown-ticks 80
  :on-key-down groundshock/groundshock-on-key-down
  :on-key-tick groundshock/groundshock-on-key-tick
  :on-key-up groundshock/groundshock-on-key-up
  :on-key-abort groundshock/groundshock-on-key-abort
  :prerequisites [{:skill-id :directed-shock :min-exp 0.0}])

(defskill vec-accel
  :id :vec-accel
  :category-id :vecmanip
  :name-key "ability.skill.vecmanip.vec_accel"
  :description-key "ability.skill.vecmanip.vec_accel.desc"
  :icon "textures/abilities/vecmanip/skills/vec_accel.png"
  :level 2
  :controllable? false
  :ctrl-id :vec-accel
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :cooldown-ticks 80
  :on-key-down vec-accel/vec-accel-on-key-down
  :on-key-tick vec-accel/vec-accel-on-key-tick
  :on-key-up vec-accel/vec-accel-on-key-up
  :on-key-abort vec-accel/vec-accel-on-key-abort
  :prerequisites [{:skill-id :directed-shock :min-exp 0.0}])

(defskill vec-deviation
  :id :vec-deviation
  :category-id :vecmanip
  :name-key "ability.skill.vecmanip.vec_deviation"
  :description-key "ability.skill.vecmanip.vec_deviation.desc"
  :icon "textures/abilities/vecmanip/skills/vec_deviation.png"
  :level 2
  :controllable? true
  :ctrl-id :vec-deviation
  :cp-consume-speed 13.0
  :overload-consume-speed 0.0
  :cooldown-ticks 0
  :on-key-down vec-deviation/vec-deviation-on-key-down
  :on-key-tick vec-deviation/vec-deviation-on-key-tick
  :on-key-up vec-deviation/vec-deviation-on-key-up
  :on-key-abort vec-deviation/vec-deviation-on-key-abort
  :prerequisites [{:skill-id :vec-accel :min-exp 0.4}])

(defskill vec-reflection
  :id :vec-reflection
  :category-id :vecmanip
  :name-key "ability.skill.vecmanip.vec_reflection"
  :description-key "ability.skill.vecmanip.vec_reflection.desc"
  :icon "textures/abilities/vecmanip/skills/vec_reflection.png"
  :level 4
  :controllable? true
  :ctrl-id :vec-reflection
  :cp-consume-speed 15.0
  :overload-consume-speed 0.0
  :cooldown-ticks 0
  :on-key-down vec-reflection/vec-reflection-on-key-down
  :on-key-tick vec-reflection/vec-reflection-on-key-tick
  :on-key-up vec-reflection/vec-reflection-on-key-up
  :on-key-abort vec-reflection/vec-reflection-on-key-abort
  :prerequisites [{:skill-id :vec-deviation :min-exp 0.0}])

(defskill directed-blastwave
  :id :directed-blastwave
  :category-id :vecmanip
  :name-key "ability.skill.vecmanip.directed_blastwave"
  :description-key "ability.skill.vecmanip.directed_blastwave.desc"
  :icon "textures/abilities/vecmanip/skills/dir_blast.png"
  :level 3
  :controllable? false
  :ctrl-id :directed-blastwave
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :cooldown-ticks 80
  :on-key-down directed-blastwave/directed-blastwave-on-key-down
  :on-key-tick directed-blastwave/directed-blastwave-on-key-tick
  :on-key-up directed-blastwave/directed-blastwave-on-key-up
  :on-key-abort directed-blastwave/directed-blastwave-on-key-abort
  :prerequisites [{:skill-id :groundshock :min-exp 0.5}])

(defskill storm-wing
  :id :storm-wing
  :category-id :vecmanip
  :name-key "ability.skill.vecmanip.storm_wing"
  :description-key "ability.skill.vecmanip.storm_wing.desc"
  :icon "textures/abilities/vecmanip/skills/storm_wing.png"
  :level 3
  :controllable? true
  :ctrl-id :storm-wing
  :cp-consume-speed 40.0
  :overload-consume-speed 10.0
  :cooldown-ticks 30
  :on-key-down storm-wing/storm-wing-on-key-down
  :on-key-tick storm-wing/storm-wing-on-key-tick
  :on-key-up storm-wing/storm-wing-on-key-up
  :on-key-abort storm-wing/storm-wing-on-key-abort
  :prerequisites [{:skill-id :vec-accel :min-exp 0.6}])

(defskill blood-retrograde
  :id :blood-retrograde
  :category-id :vecmanip
  :name-key "ability.skill.vecmanip.blood_retrograde"
  :description-key "ability.skill.vecmanip.blood_retrograde.desc"
  :icon "textures/abilities/vecmanip/skills/blood_retro.png"
  :level 4
  :controllable? false
  :ctrl-id :blood-retrograde
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :cooldown-ticks 90
  :on-key-down blood-retrograde/blood-retrograde-on-key-down
  :on-key-tick blood-retrograde/blood-retrograde-on-key-tick
  :on-key-up blood-retrograde/blood-retrograde-on-key-up
  :on-key-abort blood-retrograde/blood-retrograde-on-key-abort
  :prerequisites [{:skill-id :vec-reflection :min-exp 0.5}])

(defskill plasma-cannon
  :id :plasma-cannon
  :category-id :vecmanip
  :name-key "ability.skill.vecmanip.plasma_cannon"
  :description-key "ability.skill.vecmanip.plasma_cannon.desc"
  :icon "textures/abilities/vecmanip/skills/plasma_cannon.png"
  :level 5
  :controllable? false
  :ctrl-id :plasma-cannon
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :cooldown-ticks 1000
  :on-key-down plasma-cannon/plasma-cannon-on-key-down
  :on-key-tick plasma-cannon/plasma-cannon-on-key-tick
  :on-key-up plasma-cannon/plasma-cannon-on-key-up
  :on-key-abort plasma-cannon/plasma-cannon-on-key-abort
  :prerequisites [{:skill-id :directed-blastwave :min-exp 0.7}])

(defonce ^:private ability-content-installed? (atom false))

(defn init-ability-content!
  []
  (when (compare-and-set! ability-content-installed? false true)
    (doseq [[_sym var] (ns-publics *ns*)]
      (let [v (var-get var)]
        (when (map? v)
          (case (:ac/content-type v)
            :category (category/register-category! (dissoc v :ac/content-type))
            :skill (skill/register-skill! (dissoc v :ac/content-type))
            nil))))
    (log/info "Ability content initialized")))

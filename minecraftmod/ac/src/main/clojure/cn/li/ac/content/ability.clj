(ns cn.li.ac.content.ability
  "This namespace is pure content data loaded by ac core startup."
  (:require [cn.li.ac.ability.dsl :refer [defcategory defskill]]
            [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.service.learning :as learning]
            [cn.li.ac.ability.skill :as skill]
            [cn.li.ac.ability.event :as ability-evt]
            [cn.li.ac.ability.context :as ctx]
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

(defcategory meltdowner-category
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
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :cooldown-ticks 14
  :pattern :hold-channel
  :cost {:tick {:mode :runtime-speed
                :cp-speed 0.8
                :overload-speed 0.7}}
  :actions {:tick! arc-gen-on-key-tick
            :up! arc-gen-on-key-up})

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
  :pattern :instant
  :cooldown {:mode :manual}
  :cost {:down {:cp 'cn.li.ac.content.ability.electromaster.thunder-bolt/thunder-bolt-cost-down-cp
                :overload 'cn.li.ac.content.ability.electromaster.thunder-bolt/thunder-bolt-cost-down-overload}}
  :actions {:perform! 'cn.li.ac.content.ability.electromaster.thunder-bolt/thunder-bolt-perform!}
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
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :cooldown-ticks 1
  :pattern :charge-window
  :cooldown {:mode :manual}
  :cost {:down {:overload 'cn.li.ac.content.ability.electromaster.thunder-clap/thunder-clap-cost-down-overload}
         :tick {:cp 'cn.li.ac.content.ability.electromaster.thunder-clap/thunder-clap-cost-tick-cp}}
  :actions {:down! 'cn.li.ac.content.ability.electromaster.thunder-clap/thunder-clap-on-key-down
            :tick! 'cn.li.ac.content.ability.electromaster.thunder-clap/thunder-clap-on-key-tick
            :up! 'cn.li.ac.content.ability.electromaster.thunder-clap/thunder-clap-on-key-up
            :abort! 'cn.li.ac.content.ability.electromaster.thunder-clap/thunder-clap-on-key-abort}
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
  :pattern :charge-window
  :cost {:down {:overload 'cn.li.ac.content.ability.electromaster.current-charging/current-charging-cost-down-overload}
         :tick {:cp 'cn.li.ac.content.ability.electromaster.current-charging/current-charging-cost-tick-cp}}
  :actions {:down! 'cn.li.ac.content.ability.electromaster.current-charging/current-charging-on-key-down
            :tick! 'cn.li.ac.content.ability.electromaster.current-charging/current-charging-on-key-tick
            :up! 'cn.li.ac.content.ability.electromaster.current-charging/current-charging-on-key-up
            :abort! 'cn.li.ac.content.ability.electromaster.current-charging/current-charging-on-key-abort}
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
  :pattern :charge-window
  :cooldown {:mode :manual}
  :cost {:down {:overload 'cn.li.ac.content.ability.electromaster.body-intensify/body-intensify-cost-down-overload}
         :tick {:cp 'cn.li.ac.content.ability.electromaster.body-intensify/body-intensify-cost-tick-cp}}
  :actions {:down! 'cn.li.ac.content.ability.electromaster.body-intensify/body-intensify-on-key-down
            :tick! 'cn.li.ac.content.ability.electromaster.body-intensify/body-intensify-on-key-tick
            :up! 'cn.li.ac.content.ability.electromaster.body-intensify/body-intensify-on-key-up
            :abort! 'cn.li.ac.content.ability.electromaster.body-intensify/body-intensify-on-key-abort}
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
  :pattern :charge-window
  :cooldown {:mode :manual}
  :cost {:down {:overload 'cn.li.ac.content.ability.electromaster.mag-movement/mag-movement-cost-down-overload
                :creative? 'cn.li.ac.content.ability.electromaster.mag-movement/mag-movement-cost-creative?}
         :tick {:cp 'cn.li.ac.content.ability.electromaster.mag-movement/mag-movement-cost-tick-cp
                :creative? 'cn.li.ac.content.ability.electromaster.mag-movement/mag-movement-cost-creative?}}
  :actions {:down! 'cn.li.ac.content.ability.electromaster.mag-movement/mag-movement-on-key-down
            :tick! 'cn.li.ac.content.ability.electromaster.mag-movement/mag-movement-on-key-tick
            :up! 'cn.li.ac.content.ability.electromaster.mag-movement/mag-movement-on-key-up
            :abort! 'cn.li.ac.content.ability.electromaster.mag-movement/mag-movement-on-key-abort}
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
  :pattern :release-cast
  :cooldown {:mode :manual}
  :cost {:up {:cp 'cn.li.ac.content.ability.electromaster.mag-manip/mag-manip-cost-up-cp
              :overload 'cn.li.ac.content.ability.electromaster.mag-manip/mag-manip-cost-up-overload
              :creative? 'cn.li.ac.content.ability.electromaster.mag-manip/mag-manip-cost-creative?}}
  :actions {:down! 'cn.li.ac.content.ability.electromaster.mag-manip/mag-manip-on-key-down
            :tick! 'cn.li.ac.content.ability.electromaster.mag-manip/mag-manip-on-key-tick
            :up! 'cn.li.ac.content.ability.electromaster.mag-manip/mag-manip-on-key-up
            :abort! 'cn.li.ac.content.ability.electromaster.mag-manip/mag-manip-on-key-abort}
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
  :pattern :charge-window
  :cooldown {:mode :manual}
  :cost {:down {:cp 'cn.li.ac.content.ability.electromaster.railgun/railgun-cost-down-cp
                :overload 'cn.li.ac.content.ability.electromaster.railgun/railgun-cost-down-overload
                :creative? 'cn.li.ac.content.ability.electromaster.railgun/railgun-cost-creative?}
         :tick {:cp 'cn.li.ac.content.ability.electromaster.railgun/railgun-cost-tick-cp
                :overload 'cn.li.ac.content.ability.electromaster.railgun/railgun-cost-tick-overload
                :creative? 'cn.li.ac.content.ability.electromaster.railgun/railgun-cost-creative?}}
  :actions {:down! 'cn.li.ac.content.ability.electromaster.railgun/railgun-on-key-down
            :tick! 'cn.li.ac.content.ability.electromaster.railgun/railgun-on-key-tick
            :up! 'cn.li.ac.content.ability.electromaster.railgun/railgun-on-key-up
            :abort! 'cn.li.ac.content.ability.electromaster.railgun/railgun-on-key-abort}
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
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :cooldown-ticks 18
  :pattern :hold-channel
  :cost {:tick {:mode :runtime-speed
                :cp-speed 0.9
                :overload-speed 0.8}}
  :actions {:tick! vec-manip-on-key-tick
            :up! vec-manip-on-key-up})

(defskill storm-wind
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
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :cooldown-ticks 200
  :pattern :charge-window
  :cooldown {:mode :manual}
  :cost {:down {:overload 'cn.li.ac.content.ability.meltdowner.meltdowner/meltdowner-cost-down-overload}
         :tick {:cp 'cn.li.ac.content.ability.meltdowner.meltdowner/meltdowner-cost-tick-cp}}
  :actions {:down! 'cn.li.ac.content.ability.meltdowner.meltdowner/meltdowner-on-key-down
            :tick! 'cn.li.ac.content.ability.meltdowner.meltdowner/meltdowner-on-key-tick
            :up! 'cn.li.ac.content.ability.meltdowner.meltdowner/meltdowner-on-key-up
            :abort! 'cn.li.ac.content.ability.meltdowner.meltdowner/meltdowner-on-key-abort})

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
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :cooldown-ticks 20
  :pattern :release-cast
  :cooldown {:mode :manual}
  :cost {:up {:cp 'cn.li.ac.content.ability.teleporter.mark-teleport/mark-teleport-cost-up-cp
              :overload 'cn.li.ac.content.ability.teleporter.mark-teleport/mark-teleport-cost-up-overload
              :creative? 'cn.li.ac.content.ability.teleporter.mark-teleport/mark-teleport-cost-creative?}}
  :actions {:down! 'cn.li.ac.content.ability.teleporter.mark-teleport/mark-teleport-on-key-down
            :tick! 'cn.li.ac.content.ability.teleporter.mark-teleport/mark-teleport-on-key-tick
            :up! 'cn.li.ac.content.ability.teleporter.mark-teleport/mark-teleport-on-key-up
            :abort! 'cn.li.ac.content.ability.teleporter.mark-teleport/mark-teleport-on-key-abort}
  :fx {:start {:topic :mark-teleport/fx-start
               :payload (fn [_] {})}
       :update {:topic :mark-teleport/fx-update
                :payload 'cn.li.ac.content.ability.teleporter.mark-teleport/mark-teleport-fx-update-payload}
       :perform {:topic :mark-teleport/fx-perform
                 :payload 'cn.li.ac.content.ability.teleporter.mark-teleport/mark-teleport-fx-perform-payload}
       :end {:topic :mark-teleport/fx-end
             :payload (fn [_] {})}})

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
  :pattern :release-cast
  :cooldown {:mode :manual}
  :actions {:down! 'cn.li.ac.content.ability.teleporter.location-teleport/location-teleport-on-key-down
            :tick! 'cn.li.ac.content.ability.teleporter.location-teleport/location-teleport-on-key-tick
            :up! 'cn.li.ac.content.ability.teleporter.location-teleport/location-teleport-on-key-up
            :abort! 'cn.li.ac.content.ability.teleporter.location-teleport/location-teleport-on-key-abort}
  :prerequisites [{:skill-id :mark-teleport :min-exp 0.5}])

;; Vector Manipulation skills
(defskill directed-shock
  :id :directed-shock
  :category-id :vecmanip
  :name-key "ability.skill.vecmanip.directed_shock"
  :description-key "ability.skill.vecmanip.directed_shock.desc"
  :icon "textures/abilities/vecmanip/skills/dir_shock.png"
  :ui-position [16 45]
  :level 1
  :controllable? false
  :ctrl-id :directed-shock
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :cooldown-ticks 60
  :pattern :release-cast
  :cooldown {:mode :manual}
  :cost {:up {:cp 'cn.li.ac.content.ability.vecmanip.directed-shock/directed-shock-cost-up-cp
              :overload 'cn.li.ac.content.ability.vecmanip.directed-shock/directed-shock-cost-up-overload}}
  :actions {:down! 'cn.li.ac.content.ability.vecmanip.directed-shock/directed-shock-on-key-down
            :tick! 'cn.li.ac.content.ability.vecmanip.directed-shock/directed-shock-on-key-tick
            :up! 'cn.li.ac.content.ability.vecmanip.directed-shock/directed-shock-on-key-up
            :abort! 'cn.li.ac.content.ability.vecmanip.directed-shock/directed-shock-on-key-abort})

(defskill groundshock
  :id :groundshock
  :category-id :vecmanip
  :name-key "ability.skill.vecmanip.groundshock"
  :description-key "ability.skill.vecmanip.groundshock.desc"
  :icon "textures/abilities/vecmanip/skills/ground_shock.png"
  :ui-position [64 85]
  :level 1
  :controllable? false
  :ctrl-id :groundshock
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :cooldown-ticks 80
  :pattern :release-cast
  :cooldown {:mode :manual}
  :cost {:up {:cp 'cn.li.ac.content.ability.vecmanip.groundshock/groundshock-cost-up-cp
              :overload 'cn.li.ac.content.ability.vecmanip.groundshock/groundshock-cost-up-overload}}
  :actions {:down! 'cn.li.ac.content.ability.vecmanip.groundshock/groundshock-on-key-down
            :tick! 'cn.li.ac.content.ability.vecmanip.groundshock/groundshock-on-key-tick
            :up! 'cn.li.ac.content.ability.vecmanip.groundshock/groundshock-on-key-up
            :abort! 'cn.li.ac.content.ability.vecmanip.groundshock/groundshock-on-key-abort}
  :prerequisites [{:skill-id :directed-shock :min-exp 0.0}])

(defskill vec-accel
  :id :vec-accel
  :category-id :vecmanip
  :name-key "ability.skill.vecmanip.vec_accel"
  :description-key "ability.skill.vecmanip.vec_accel.desc"
  :icon "textures/abilities/vecmanip/skills/vec_accel.png"
  :ui-position [76 40]
  :level 2
  :controllable? false
  :ctrl-id :vec-accel
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :cooldown-ticks 80
  :pattern :hold-charge-release
  :cooldown {:mode :manual}
  :state {:max-charge 20}
  :cost {:up {:cp 'cn.li.ac.content.ability.vecmanip.vec-accel/vec-accel-cost-up-cp
              :overload 'cn.li.ac.content.ability.vecmanip.vec-accel/vec-accel-cost-up-overload}}
  :actions {:tick! 'cn.li.ac.content.ability.vecmanip.vec-accel/vec-accel-tick!
            :perform! 'cn.li.ac.content.ability.vecmanip.vec-accel/vec-accel-perform!
            :abort! 'cn.li.ac.content.ability.vecmanip.vec-accel/vec-accel-abort!}
  :fx {:start {:topic :vec-accel/fx-start
               :payload (fn [_] {})}
       :update {:topic :vec-accel/fx-update
                :payload (fn [{:keys [ctx-id charge-ticks]}]
                           (let [st (:skill-state (ctx/get-context ctx-id))]
                             {:charge-ticks (long (max 0 (or charge-ticks 0)))
                              :can-perform? (boolean (:can-perform? st))
                              :look-dir (or (:look-dir st) {:x 0.0 :y 0.0 :z 1.0})
                              :init-vel (or (:init-vel st) {:x 0.0 :y 0.0 :z 1.0})}))}
       :perform {:topic :vec-accel/fx-perform
                 :payload (fn [_] {})}
       :end {:topic :vec-accel/fx-end
             :payload (fn [{:keys [ctx-id]}]
                        (let [st (:skill-state (ctx/get-context ctx-id))]
                          {:performed? (boolean (:performed? st))}))}}
  :prerequisites [{:skill-id :directed-shock :min-exp 0.0}])

(defskill vec-deviation
  :id :vec-deviation
  :category-id :vecmanip
  :name-key "ability.skill.vecmanip.vec_deviation"
  :description-key "ability.skill.vecmanip.vec_deviation.desc"
  :icon "textures/abilities/vecmanip/skills/vec_deviation.png"
  :ui-position [145 53]
  :level 2
  :controllable? true
  :ctrl-id :vec-deviation
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :cooldown-ticks 0
  :pattern :toggle
  :cooldown {:mode :manual}
  :cost {:tick {:cp 'cn.li.ac.content.ability.vecmanip.vec-deviation/vec-deviation-cost-tick-cp}}
  :actions {:activate! 'cn.li.ac.content.ability.vecmanip.vec-deviation/vec-deviation-activate!
            :deactivate! 'cn.li.ac.content.ability.vecmanip.vec-deviation/vec-deviation-deactivate!
            :tick! 'cn.li.ac.content.ability.vecmanip.vec-deviation/vec-deviation-tick!
            :abort! 'cn.li.ac.content.ability.vecmanip.vec-deviation/vec-deviation-abort!}
  :fx {:start {:topic :vec-deviation/fx-start :payload (fn [_] {})}
       :end {:topic :vec-deviation/fx-end :payload (fn [_] {})}}
  :prerequisites [{:skill-id :vec-accel :min-exp 0.4}])

(defskill vec-reflection
  :id :vec-reflection
  :category-id :vecmanip
  :name-key "ability.skill.vecmanip.vec_reflection"
  :description-key "ability.skill.vecmanip.vec_reflection.desc"
  :icon "textures/abilities/vecmanip/skills/vec_reflection.png"
  :ui-position [210 50]
  :level 4
  :controllable? true
  :ctrl-id :vec-reflection
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :cooldown-ticks 0
  :pattern :release-cast
  :cost {:tick {:cp 'cn.li.ac.content.ability.vecmanip.vec-reflection/vec-reflection-cost-tick-cp}}
  :actions {:down! 'cn.li.ac.content.ability.vecmanip.vec-reflection/vec-reflection-on-key-down
            :tick! 'cn.li.ac.content.ability.vecmanip.vec-reflection/vec-reflection-on-key-tick
            :up! 'cn.li.ac.content.ability.vecmanip.vec-reflection/vec-reflection-on-key-up
            :abort! 'cn.li.ac.content.ability.vecmanip.vec-reflection/vec-reflection-on-key-abort}
  :prerequisites [{:skill-id :vec-deviation :min-exp 0.0}])

(defskill directed-blastwave
  :id :directed-blastwave
  :category-id :vecmanip
  :name-key "ability.skill.vecmanip.directed_blastwave"
  :description-key "ability.skill.vecmanip.directed_blastwave.desc"
  :icon "textures/abilities/vecmanip/skills/dir_blast.png"
  :ui-position [136 80]
  :level 3
  :controllable? false
  :ctrl-id :directed-blastwave
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :cooldown-ticks 80
  :pattern :release-cast
  :cooldown {:mode :manual}
  :cost {:up {:cp 'cn.li.ac.content.ability.vecmanip.directed-blastwave/directed-blastwave-cost-up-cp
              :overload 'cn.li.ac.content.ability.vecmanip.directed-blastwave/directed-blastwave-cost-up-overload}}
  :actions {:down! 'cn.li.ac.content.ability.vecmanip.directed-blastwave/directed-blastwave-on-key-down
            :tick! 'cn.li.ac.content.ability.vecmanip.directed-blastwave/directed-blastwave-on-key-tick
            :up! 'cn.li.ac.content.ability.vecmanip.directed-blastwave/directed-blastwave-on-key-up
            :abort! 'cn.li.ac.content.ability.vecmanip.directed-blastwave/directed-blastwave-on-key-abort}
  :prerequisites [{:skill-id :groundshock :min-exp 0.5}])

(defskill storm-wing
  :id :storm-wing
  :category-id :vecmanip
  :name-key "ability.skill.vecmanip.storm_wing"
  :description-key "ability.skill.vecmanip.storm_wing.desc"
  :icon "textures/abilities/vecmanip/skills/storm_wing.png"
  :ui-position [130 20]
  :level 3
  :controllable? true
  :ctrl-id :storm-wing
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :cooldown-ticks 0
  :pattern :release-cast
  :cooldown {:mode :manual}
  :cost {:tick {:cp 'cn.li.ac.content.ability.vecmanip.storm-wing/storm-wing-cost-tick-cp
                :overload 'cn.li.ac.content.ability.vecmanip.storm-wing/storm-wing-cost-tick-overload
                :creative? 'cn.li.ac.content.ability.vecmanip.storm-wing/storm-wing-cost-creative?}}
  :actions {:down! 'cn.li.ac.content.ability.vecmanip.storm-wing/storm-wing-on-key-down
            :tick! 'cn.li.ac.content.ability.vecmanip.storm-wing/storm-wing-on-key-tick
            :up! 'cn.li.ac.content.ability.vecmanip.storm-wing/storm-wing-on-key-up
            :abort! 'cn.li.ac.content.ability.vecmanip.storm-wing/storm-wing-on-key-abort}
  :prerequisites [{:skill-id :vec-accel :min-exp 0.6}])

(defskill blood-retrograde
  :id :blood-retrograde
  :category-id :vecmanip
  :name-key "ability.skill.vecmanip.blood_retrograde"
  :description-key "ability.skill.vecmanip.blood_retrograde.desc"
  :icon "textures/abilities/vecmanip/skills/blood_retro.png"
  :ui-position [204 83]
  :level 4
  :controllable? true
  :ctrl-id :blood-retrograde
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :cooldown-ticks 90
  :pattern :release-cast
  :cooldown {:mode :manual}
  :cost {:tick {:cp 'cn.li.ac.content.ability.vecmanip.blood-retrograde/blood-retrograde-cost-release-cp
                :overload 'cn.li.ac.content.ability.vecmanip.blood-retrograde/blood-retrograde-cost-release-overload}
         :up {:cp 'cn.li.ac.content.ability.vecmanip.blood-retrograde/blood-retrograde-cost-release-cp
              :overload 'cn.li.ac.content.ability.vecmanip.blood-retrograde/blood-retrograde-cost-release-overload}}
  :actions {:down! 'cn.li.ac.content.ability.vecmanip.blood-retrograde/blood-retrograde-on-key-down
            :tick! 'cn.li.ac.content.ability.vecmanip.blood-retrograde/blood-retrograde-on-key-tick
            :up! 'cn.li.ac.content.ability.vecmanip.blood-retrograde/blood-retrograde-on-key-up
            :abort! 'cn.li.ac.content.ability.vecmanip.blood-retrograde/blood-retrograde-on-key-abort}
  :prerequisites [{:skill-id :directed-blastwave :min-exp 0.0}])

(defskill plasma-cannon
  :id :plasma-cannon
  :category-id :vecmanip
  :name-key "ability.skill.vecmanip.plasma_cannon"
  :description-key "ability.skill.vecmanip.plasma_cannon.desc"
  :icon "textures/abilities/vecmanip/skills/plasma_cannon.png"
  :ui-position [175 14]
  :level 5
  :controllable? false
  :ctrl-id :plasma-cannon
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :cooldown-ticks 1000
  :pattern :charge-window
  :cooldown {:mode :manual}
  :cost {:down {:overload 'cn.li.ac.content.ability.vecmanip.plasma-cannon/plasma-cannon-cost-down-overload}
         :tick {:cp 'cn.li.ac.content.ability.vecmanip.plasma-cannon/plasma-cannon-cost-tick-cp}}
  :actions {:down! 'cn.li.ac.content.ability.vecmanip.plasma-cannon/plasma-cannon-on-key-down
            :tick! 'cn.li.ac.content.ability.vecmanip.plasma-cannon/plasma-cannon-on-key-tick
            :up! 'cn.li.ac.content.ability.vecmanip.plasma-cannon/plasma-cannon-on-key-up
            :abort! 'cn.li.ac.content.ability.vecmanip.plasma-cannon/plasma-cannon-on-key-abort}
  :prerequisites [{:skill-id :directed-blastwave :min-exp 0.7}])

(defonce ^:private ability-content-installed? (atom false))

(defn- normalize-skill-content
  "Normalize every skill declaration into the new DSL contract before registration.
  This is the single migration choke-point for all skills in this namespace."
  [skill-map]
  (-> skill-map
      ;; Legacy callback keys are removed globally (non-compatible migration).
      (dissoc :on-key-down :on-key-tick :on-key-up :on-key-abort)
      ;; Freeze policy maps so all skills share one schema.
      (update :cooldown #(or % {:mode :default}))
      (update :cost #(or % {}))
      (update :actions #(or % {}))
      (update :fx #(or % {}))
      (update :targeting #(or % {}))
      (update :transitions #(or % {}))
      (update :exp-policy #(or % {}))
      (update :cooldown-policy #(or % {}))
      (update :state #(or % {}))))

(defn init-ability-content!
  []
  (when (compare-and-set! ability-content-installed? false true)
    (doseq [[_sym var] (ns-publics *ns*)]
      (let [v (var-get var)]
        (when (map? v)
          (case (:ac/content-type v)
            :category (category/register-category! (dissoc v :ac/content-type))
            :skill (skill/register-skill! (normalize-skill-content (dissoc v :ac/content-type)))
            nil))))
    (log/info "Ability content initialized")))

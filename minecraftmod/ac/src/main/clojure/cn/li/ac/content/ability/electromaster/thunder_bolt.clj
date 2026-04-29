(ns cn.li.ac.content.ability.electromaster.thunder-bolt
  "ThunderBolt skill - instant targeted lightning strike with AOE damage.

  Pattern: :instant
  Cost: CP lerp(280,420), overload lerp(50,27) by exp
  Cooldown: lerp(120,50) ticks by exp
  Exp: +0.005 effective / +0.003 ineffective"
  (:require [cn.li.ac.ability.dsl :refer [defskill!]]
            [cn.li.ac.ability.util.balance :refer [by-exp]]
            [cn.li.ac.ability.server.effect.geom]
            [cn.li.ac.ability.server.effect.damage]
            [cn.li.ac.ability.server.effect.world]
            [cn.li.ac.ability.server.effect.potion]
            [cn.li.ac.ability.server.effect.fx]))

(defskill! thunder-bolt
  :id          :thunder-bolt
  :category-id :electromaster
  :name-key    "ability.skill.electromaster.thunder_bolt"
  :description-key "ability.skill.electromaster.thunder_bolt.desc"
  :icon        "textures/abilities/electromaster/skills/thunder_bolt.png"
  :ui-position [86 67]
  :level       2
  :controllable? false
  :ctrl-id     :thunder-bolt
  :pattern     :instant
  :cost        {:down {:cp       (by-exp 280.0 420.0)
                       :overload (by-exp 50.0  27.0)}}
  :cooldown-ticks (by-exp 120 50)
  :exp         {:effective 0.005 :ineffective 0.003}
  :perform     [[:spawn-entity-from-player {:entity-id "my_mod:entity_arc"
                                            :speed 0.0}]
                [:aim-raycast  {:range 20}]
                [:spawn-lightning {:at :hit}]
                [:damage-direct  {:target     :hit
                                  :amount     (by-exp 10.0 25.0)
                                  :damage-type :lightning}]
                [:damage-aoe     {:center     :hit
                                  :radius     8.0
                                  :amount     (by-exp 6.0 15.0)
                                  :damage-type :lightning}]
                [:potion-roll    {:target     :hit
                                  :chance     0.8
                                  :effect-id  :slowness
                                  :ticks      40
                                  :amplifier  3}]
                [:fx             {:topic   :thunder-bolt/fx-perform
                                  :payload (fn [evt]
                                             {:start (:eye-pos evt)
                                              :end   (:hit evt)})}]]
  :prerequisites [{:skill-id :arc-gen         :min-exp 0.0}
                  {:skill-id :current-charging :min-exp 0.7}])

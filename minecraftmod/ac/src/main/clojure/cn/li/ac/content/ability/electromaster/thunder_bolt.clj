(ns cn.li.ac.content.ability.electromaster.thunder-bolt
  "ThunderBolt skill - instant targeted lightning strike with AOE damage.

  Pattern: :instant
  Cost: CP lerp(280,420), overload lerp(50,27) by exp
  Cooldown: lerp(120,50) ticks by exp
  Exp: +0.005 effective / +0.003 ineffective"
  (:require [cn.li.ac.ability.dsl :refer [defskill!]]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.server.effect.geom]
            [cn.li.ac.ability.server.effect.damage]
            [cn.li.ac.ability.server.effect.world]
            [cn.li.ac.ability.server.effect.potion]
            [cn.li.ac.ability.server.effect.fx]))

(def ^:private thunder-bolt-skill-id :thunder-bolt)

(defn- cfg-double [field-id]
  (skill-config/tunable-double thunder-bolt-skill-id field-id))

(defn- cfg-int [field-id]
  (skill-config/tunable-int thunder-bolt-skill-id field-id))

(defn- cfg-lerp [field-id exp]
  (skill-config/lerp-double thunder-bolt-skill-id field-id exp))

(defn- evt-lerp [field-id]
  (fn [{:keys [exp]}]
    (cfg-lerp field-id (double (or exp 0.0)))))

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
  :cost        {:down {:cp       (evt-lerp :cost.down.cp)
                       :overload (evt-lerp :cost.down.overload)}}
  :cooldown-ticks (fn [{:keys [exp]}]
                    (skill-config/lerp-int thunder-bolt-skill-id
                                           :cooldown.ticks
                                           (double (or exp 0.0))))
  :exp         {:effective (fn [_] (cfg-double :progression.exp-effective))
                :ineffective (fn [_] (cfg-double :progression.exp-ineffective))}
  :perform     [[:spawn-entity-from-player {:entity-id "my_mod:entity_arc"
                                            :speed 0.0}]
                [:aim-raycast  {:range (fn [_] (cfg-double :targeting.range))}]
                [:spawn-lightning {:at :hit}]
                [:damage-direct  {:target     :hit
                                  :amount     (evt-lerp :combat.direct-damage)
                                  :damage-type :lightning}]
                [:damage-aoe     {:center     :hit
                                  :radius     (fn [_] (cfg-double :combat.aoe-radius))
                                  :amount     (evt-lerp :combat.aoe-damage)
                                  :damage-type :lightning}]
                [:potion-roll    {:target     :hit
                                  :chance     (fn [_] (skill-config/probability thunder-bolt-skill-id
                                                                                 :effect.slowness-chance))
                                  :effect-id  :slowness
                                  :ticks      (fn [_] (cfg-int :effect.slowness-duration-ticks))
                                  :amplifier  (fn [_] (cfg-int :effect.slowness-amplifier))}]
                [:fx             {:topic   :thunder-bolt/fx-perform
                                  :payload (fn [evt]
                                             {:start (:eye-pos evt)
                                              :end   (:hit evt)})}]]
  :prerequisites [{:skill-id :arc-gen         :min-exp 0.0}
                  {:skill-id :current-charging :min-exp 0.7}])

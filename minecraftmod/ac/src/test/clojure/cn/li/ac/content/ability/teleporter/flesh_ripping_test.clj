(ns cn.li.ac.content.ability.teleporter.flesh-ripping-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.service.dispatcher :as ctx]
            [cn.li.ac.ability.server.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.server.effect.geom :as geom]
            [cn.li.ac.content.ability.teleporter.flesh-ripping :as flesh]
            [cn.li.ac.content.ability.teleporter.tp-skill-helper :as helper]
            [cn.li.mcmod.platform.potion-effects :as potion]))

(deftest flesh-ripping-hit-critical-emits-crit-fx-test
  (let [exp-calls* (atom [])
        cooldown-calls* (atom [])
        fx-calls* (atom [])
        potion-calls* (atom [])
        damage-calls* (atom [])]
    (with-redefs [helper/skill-exp (fn [_ _] 0.5)
                  helper/cfg-lerp (fn [_ field _]
                                    (case field
                                      :combat.damage 8.0
                                      :targeting.range 15.0
                                      :cooldown.ticks 20.0
                                      0.0))
                  helper/cfg-lerp-int (fn [& _] 20)
                  helper/cfg-double (fn [_ field]
                                      (case field
                                        :progression.exp-hit 0.003
                                        0.0))
                  helper/cfg-probability (fn [_ _] 1.0)
                  helper/cfg-int (fn [_ field]
                                   (case field
                                     :effect.nausea-duration-ticks 60
                                     :effect.nausea-amplifier 0
                                     0))
                  helper/raycast-entity (fn [_ _]
                                          {:entity-uuid "target-1"
                                           :entity-x 1.0
                                           :entity-y 2.0
                                           :entity-z 3.0})
                  geom/world-id-of (fn [_] "minecraft:overworld")
                  helper/deal-magic-damage! (fn [_ world-id target-uuid damage]
                                              (swap! damage-calls* conj [world-id target-uuid damage])
                                              {:critical? true
                                               :crit-level 2
                                               :damage-after damage
                                               :applied? true})
                  potion/apply-potion-effect! (fn [_ target-id effect duration amplifier]
                                                (swap! potion-calls* conj [target-id effect duration amplifier])
                                                true)
                  skill-effects/add-skill-exp! (fn [player-id skill-id amount]
                                                 (swap! exp-calls* conj [player-id skill-id amount])
                                                 nil)
                  skill-effects/set-main-cooldown! (fn [player-id skill-id ticks]
                                                     (swap! cooldown-calls* conj [player-id skill-id ticks])
                                                     nil)
                  ctx/ctx-send-to-client! (fn [_ctx-id channel payload]
                                            (swap! fx-calls* conj [channel payload])
                                            nil)
                  rand (fn [] 0.0)]
      (binding [potion/*potion-effects* :mock]
        (flesh/flesh-ripping-up! {:player-id "p1" :ctx-id "ctx-1"})))

    (is (= [["minecraft:overworld" "target-1" 8.0]] @damage-calls*))
    (is (= [["target-1" :nausea 60 0]] @potion-calls*))
    (is (= [["p1" :flesh-ripping 0.003]] @exp-calls*))
    (is (= [["p1" :flesh-ripping 20]] @cooldown-calls*))
    (is (= [[:teleporter/fx-crit-hit {:x 1.0
                                      :y 2.0
                                      :z 3.0
                                      :crit-level 2
                                      :target-uuid "target-1"
                                      :skill-id :flesh-ripping}]
            [:flesh-ripping/fx-perform {:target-x 1.0
                                        :target-y 2.0
                                        :target-z 3.0}]]
           @fx-calls*))))

(deftest flesh-ripping-miss-has-no-side-effects-test
  (let [exp-calls* (atom 0)
        cooldown-calls* (atom 0)
        fx-calls* (atom 0)
        damage-calls* (atom 0)]
    (with-redefs [helper/skill-exp (fn [_ _] 0.5)
                  helper/cfg-lerp (fn [_ _ _] 0.0)
                  helper/raycast-entity (fn [_ _] nil)
                  helper/deal-magic-damage! (fn [& _] (swap! damage-calls* inc))
                  skill-effects/add-skill-exp! (fn [& _] (swap! exp-calls* inc))
                  skill-effects/set-main-cooldown! (fn [& _] (swap! cooldown-calls* inc))
                  ctx/ctx-send-to-client! (fn [& _] (swap! fx-calls* inc))]
      (flesh/flesh-ripping-up! {:player-id "p1" :ctx-id "ctx-2"}))

    (is (= 0 @damage-calls*))
    (is (= 0 @exp-calls*))
    (is (= 0 @cooldown-calls*))
    (is (= 0 @fx-calls*))))

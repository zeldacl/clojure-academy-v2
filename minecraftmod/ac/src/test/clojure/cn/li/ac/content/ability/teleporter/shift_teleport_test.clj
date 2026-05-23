(ns cn.li.ac.content.ability.teleporter.shift-teleport-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.server.effect.geom :as geom]
            [cn.li.ac.ability.server.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.service.dispatcher :as ctx]
            [cn.li.ac.content.ability.teleporter.shift-teleport :as shift]
            [cn.li.ac.content.ability.teleporter.tp-skill-helper :as helper]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.world-effects :as world-effects]))

(deftest shift-tp-up-hit-critical-emits-crit-fx-test
  (let [exp-calls* (atom [])
        cooldown-calls* (atom [])
        fx-calls* (atom [])
        damage-calls* (atom [])]
    (with-redefs [helper/skill-exp (fn [_ _] 0.5)
                  helper/cfg-lerp (fn [_ field _]
                                    (case field
                                      :targeting.range 25.0
                                      :combat.damage 20.0
                                      :cost.down.cp 0.0
                                      :cost.down.overload 0.0
                                      :cooldown.ticks 18.0
                                      0.0))
                  helper/cfg-double (fn [_ field]
                                      (case field
                                        :targeting.eye-height 1.6
                                        :progression.exp-success 0.002
                                        0.0))
                  helper/cfg-lerp-int (fn [& _] 18)
                  helper/player-position (fn [_] {:x 1.0 :y 64.0 :z 3.0})
                  helper/player-look-vec (fn [_] {:x 0.0 :y 0.0 :z 1.0})
                  geom/world-id-of (fn [_] "minecraft:overworld")
                  raycast/raycast-blocks (fn [& _] {:x 20 :y 64 :z 21})
                  world-effects/find-entities-in-aabb (fn [& _]
                                                        [{:uuid "enemy-1" :x 12.0 :y 64.9 :z 13.4 :width 0.6 :height 1.8}
                                                         {:uuid "enemy-2" :x 13.0 :y 64.8 :z 14.3 :width 0.6 :height 1.8}
                                                         {:uuid "p1" :x 1.0 :y 64.0 :z 3.0 :width 0.6 :height 1.8}
                                                         {:uuid "off-line" :x 30.0 :y 65.0 :z 30.0 :width 0.6 :height 1.8}])
                  helper/teleport-to! (fn [& _] true)
                  helper/deal-magic-damage! (fn [_ world-id entity-uuid damage]
                                              (swap! damage-calls* conj [world-id entity-uuid damage])
                                              {:critical? (= entity-uuid "enemy-1")
                                               :crit-level 1
                                               :damage-after damage
                                               :applied? true})
                  skill-effects/add-skill-exp! (fn [player-id skill-id amount]
                                                 (swap! exp-calls* conj [player-id skill-id amount])
                                                 nil)
                  skill-effects/set-main-cooldown! (fn [player-id skill-id ticks]
                                                     (swap! cooldown-calls* conj [player-id skill-id ticks])
                                                     nil)
                  ctx/ctx-send-to-client! (fn [_ctx-id ch payload]
                                            (swap! fx-calls* conj [ch payload])
                                            nil)]
      (binding [raycast/*raycast* :mock
                world-effects/*world-effects* :mock]
        (shift/shift-tp-up! {:player-id "p1" :ctx-id "ctx-1"})))

    (is (= [["minecraft:overworld" "enemy-1" 20.0]
            ["minecraft:overworld" "enemy-2" 20.0]]
           @damage-calls*))
    (is (= [["p1" :shift-teleport 0.002]] @exp-calls*))
    (is (= [["p1" :shift-teleport 18]] @cooldown-calls*))
    (is (= [[:teleporter/fx-crit-hit {:x 12.0
                                      :y 64.9
                                      :z 13.4
                                      :crit-level 1
                                      :target-uuid "enemy-1"
                                      :skill-id :shift-teleport}]
            [:shift-tp/fx-perform {:x 20.5 :y 64.0 :z 21.5}]]
           @fx-calls*))))

(deftest shift-tp-up-no-target-no-side-effects-test
  (let [exp-calls* (atom 0)
        cooldown-calls* (atom 0)
        fx-calls* (atom 0)
        damage-calls* (atom 0)
        teleport-calls* (atom 0)]
    (with-redefs [helper/skill-exp (fn [_ _] 0.5)
                  helper/cfg-lerp (fn [_ _ _] 20.0)
                  helper/player-position (fn [_] {:x 1.0 :y 2.0 :z 3.0})
                  helper/player-look-vec (fn [_] {:x 0.0 :y 0.0 :z 1.0})
                  helper/cfg-double (fn [_ _] 1.6)
                  geom/world-id-of (fn [_] "minecraft:overworld")
                  raycast/raycast-blocks (fn [& _] nil)
                  world-effects/find-entities-in-aabb (fn [& _] [])
                  helper/teleport-to! (fn [& _] (swap! teleport-calls* inc) true)
                  helper/deal-magic-damage! (fn [& _] (swap! damage-calls* inc))
                  skill-effects/add-skill-exp! (fn [& _] (swap! exp-calls* inc))
                  skill-effects/set-main-cooldown! (fn [& _] (swap! cooldown-calls* inc))
                  ctx/ctx-send-to-client! (fn [& _] (swap! fx-calls* inc))]
      (binding [raycast/*raycast* :mock
                world-effects/*world-effects* :mock]
        (shift/shift-tp-up! {:player-id "p1" :ctx-id "ctx-2"})))

    (is (= 0 @teleport-calls*))
    (is (= 0 @damage-calls*))
    (is (= 0 @exp-calls*))
    (is (= 0 @cooldown-calls*))
    (is (= 0 @fx-calls*))))

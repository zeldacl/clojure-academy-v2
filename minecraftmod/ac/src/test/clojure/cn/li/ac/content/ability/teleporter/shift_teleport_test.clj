(ns cn.li.ac.content.ability.teleporter.shift-teleport-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.server.effect.geom :as geom]
            [cn.li.ac.ability.server.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.service.dispatcher :as ctx]
            [cn.li.ac.content.ability.teleporter.shift-teleport :as shift]
            [cn.li.ac.content.ability.teleporter.tp-skill-helper :as helper]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.world-effects :as world-effects]))

(deftest shift-tp-up-place-success-hit-critical-emits-crit-fx-test
  (let [exp-calls* (atom [])
        cooldown-calls* (atom [])
        fx-calls* (atom [])
        damage-calls* (atom [])
        consume-calls* (atom [])]
    (with-redefs [helper/skill-exp (fn [_ _] 0.5)
                  helper/cfg-lerp (fn [_ field _]
                                    (case field
                                      :targeting.range 25.0
                                      :combat.damage 20.0
                                      :cost.up.cp 260.0
                                      :cost.up.overload 40.0
                                      :cooldown.ticks 18.0
                                      0.0))
                  helper/cfg-double (fn [_ field]
                                      (case field
                                        :targeting.eye-height 1.6
                                        :progression.exp-base 0.002
                                        0.0))
                  helper/cfg-lerp-int (fn [& _] 18)
                  helper/player-position (fn [_] {:x 1.0 :y 64.0 :z 3.0})
                  helper/player-look-vec (fn [_] {:x 0.0 :y 0.0 :z 1.0})
                  geom/world-id-of (fn [_] "minecraft:overworld")
                  raycast/raycast-blocks (fn [& _] {:x 20 :y 64 :z 21 :face :up})
                  world-effects/find-entities-in-aabb (fn [& _]
                                                        [{:uuid "enemy-1" :x 12.0 :y 64.9 :z 13.4 :width 0.6 :height 1.8}
                                                         {:uuid "enemy-2" :x 13.0 :y 64.8 :z 14.3 :width 0.6 :height 1.8}
                                                         {:uuid "p1" :x 1.0 :y 64.0 :z 3.0 :width 0.6 :height 1.8}
                                                         {:uuid "off-line" :x 30.0 :y 65.0 :z 30.0 :width 0.6 :height 1.8}])
                  entity/player-main-hand-placeable-block? (fn [_] true)
                  entity/player-creative? (fn [_] false)
                  entity/player-drop-main-hand-item-at! (fn [& _] false)
                  entity/player-place-main-hand-block-at-hit! (fn [_ _ _ _ _ _]
                                                                {:placed? true
                                                                 :fallback-drop? false
                                                                 :pos {:x 20 :y 64 :z 21}
                                                                 :face :up})
                  entity/player-consume-main-hand-item! (fn [_ n]
                                                         (swap! consume-calls* conj n)
                                                         true)
                  helper/teleport-to! (fn [& _] true)
                  helper/deal-magic-damage! (fn [_ world-id entity-uuid damage]
                                              (swap! damage-calls* conj [world-id entity-uuid damage])
                                              {:critical? (= entity-uuid "enemy-1")
                                               :crit-level 1
                                               :crit-rate (if (= entity-uuid "enemy-1") 1.6 1.0)
                                               :message-key (when (= entity-uuid "enemy-1") "ability.teleporter.critical_hit")
                                               :message-args (when (= entity-uuid "enemy-1") ["x1.6"])
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
        (shift/shift-tp-up! {:player-id "p1" :ctx-id "ctx-1" :player :player :cost-ok? true})))

    (is (= [["minecraft:overworld" "enemy-1" 20.0]
            ["minecraft:overworld" "enemy-2" 20.0]]
           @damage-calls*))
    (is (= [1] @consume-calls*))
    (is (= [["p1" :shift-teleport 0.006]] @exp-calls*))
    (is (= [["p1" :shift-teleport 18]] @cooldown-calls*))
    (is (= :teleporter/fx-crit-hit (first (first @fx-calls*))))
    (is (= {:x 12.0
        :y 64.9
        :z 13.4
        :crit-level 1
        :crit-rate 1.6
        :message-key "ability.teleporter.critical_hit"
        :message-args ["x1.6"]
        :target-uuid "enemy-1"
        :skill-id :shift-teleport}
         (second (first @fx-calls*))))
    (is (= :shift-tp/fx-perform (first (second @fx-calls*))))))

(deftest shift-tp-up-critical-but-not-applied-skips-crit-fx-test
  (let [fx-calls* (atom [])]
    (with-redefs [helper/skill-exp (fn [_ _] 0.5)
                  helper/cfg-lerp (fn [_ field _]
                                    (case field
                                      :targeting.range 25.0
                                      :combat.damage 20.0
                                      :cost.up.cp 260.0
                                      :cost.up.overload 40.0
                                      :cooldown.ticks 18.0
                                      0.0))
                  helper/cfg-double (fn [_ field]
                                      (case field
                                        :targeting.eye-height 1.6
                                        :progression.exp-base 0.002
                                        0.0))
                  helper/cfg-lerp-int (fn [& _] 18)
                  helper/player-position (fn [_] {:x 1.0 :y 64.0 :z 3.0})
                  helper/player-look-vec (fn [_] {:x 0.0 :y 0.0 :z 1.0})
                  geom/world-id-of (fn [_] "minecraft:overworld")
                  raycast/raycast-blocks (fn [& _] {:x 20 :y 64 :z 21 :face :up})
                  world-effects/find-entities-in-aabb (fn [& _]
                                                        [{:uuid "enemy-1" :x 12.0 :y 64.9 :z 13.4 :width 0.6 :height 1.8}])
                  entity/player-main-hand-placeable-block? (fn [_] true)
                  entity/player-creative? (fn [_] false)
                  entity/player-drop-main-hand-item-at! (fn [& _] false)
                  entity/player-place-main-hand-block-at-hit! (fn [& _]
                                                                {:placed? true
                                                                 :fallback-drop? false
                                                                 :pos {:x 20 :y 64 :z 21}
                                                                 :face :up})
                  entity/player-consume-main-hand-item! (fn [& _] true)
                  helper/teleport-to! (fn [& _] true)
                  helper/deal-magic-damage! (fn [& _]
                                              {:critical? true
                                               :crit-level 1
                                               :applied? false})
                  skill-effects/add-skill-exp! (fn [& _] nil)
                  skill-effects/set-main-cooldown! (fn [& _] nil)
                  ctx/ctx-send-to-client! (fn [_ctx-id ch payload]
                                            (swap! fx-calls* conj [ch payload])
                                            nil)]
      (binding [raycast/*raycast* :mock
                world-effects/*world-effects* :mock]
        (shift/shift-tp-up! {:player-id "p1" :ctx-id "ctx-1b" :player :player :cost-ok? true})))

    (is (= [[:shift-tp/fx-perform {:from-x 1.0
                                   :from-y 65.6
                                   :from-z 3.0
                                   :x 20.5
                                   :y 65.0
                                   :z 21.5
                                   :target-count 1
                                   :placed? true
                                   :dropped? false}]]
           @fx-calls*))))

(deftest shift-tp-up-cost-fail-no-side-effects-test
  (let [exp-calls* (atom 0)
        cooldown-calls* (atom 0)
        fx-calls* (atom 0)
        damage-calls* (atom 0)
        teleport-calls* (atom 0)
        place-calls* (atom 0)]
    (with-redefs [helper/skill-exp (fn [_ _] 0.5)
                  helper/cfg-lerp (fn [_ _ _] 20.0)
                  helper/player-position (fn [_] {:x 1.0 :y 2.0 :z 3.0})
                  helper/player-look-vec (fn [_] {:x 0.0 :y 0.0 :z 1.0})
                  helper/cfg-double (fn [_ _] 1.6)
                  geom/world-id-of (fn [_] "minecraft:overworld")
                  raycast/raycast-blocks (fn [& _] {:x 4 :y 5 :z 6 :face :up})
                  world-effects/find-entities-in-aabb (fn [& _] [])
                  entity/player-main-hand-placeable-block? (fn [_] true)
                  entity/player-drop-main-hand-item-at! (fn [& _] true)
                  entity/player-place-main-hand-block-at-hit! (fn [& _]
                                                                (swap! place-calls* inc)
                                                                {:placed? true
                                                                 :fallback-drop? false
                                                                 :pos {:x 4 :y 5 :z 6}
                                                                 :face :up})
                  entity/player-consume-main-hand-item! (fn [& _] true)
                  helper/teleport-to! (fn [& _] (swap! teleport-calls* inc) true)
                  helper/deal-magic-damage! (fn [& _] (swap! damage-calls* inc))
                  skill-effects/add-skill-exp! (fn [& _] (swap! exp-calls* inc))
                  skill-effects/set-main-cooldown! (fn [& _] (swap! cooldown-calls* inc))
                  ctx/ctx-send-to-client! (fn [& _] (swap! fx-calls* inc))]
      (binding [raycast/*raycast* :mock
            world-effects/*world-effects* :mock]
        (shift/shift-tp-up! {:player-id "p1" :ctx-id "ctx-2" :player :player :cost-ok? false})))

    (is (= 0 @place-calls*))
    (is (= 0 @teleport-calls*))
    (is (= 0 @damage-calls*))
    (is (= 0 @exp-calls*))
    (is (= 0 @cooldown-calls*))
    (is (= 0 @fx-calls*))))

(deftest shift-tp-up-place-fail-fallback-drop-test
  (let [teleport-calls* (atom 0)
        drop-calls* (atom [])
        consume-calls* (atom 0)]
    (with-redefs [helper/skill-exp (fn [_ _] 0.5)
                  helper/cfg-lerp (fn [_ _ _] 20.0)
                  helper/cfg-double (fn [_ field]
                                      (case field
                                        :targeting.eye-height 1.6
                                        :progression.exp-base 0.002
                                        0.0))
                  helper/cfg-lerp-int (fn [& _] 30)
                  helper/player-position (fn [_] {:x 1.0 :y 2.0 :z 3.0})
                  helper/player-look-vec (fn [_] {:x 0.0 :y 0.0 :z 1.0})
                  geom/world-id-of (fn [_] "minecraft:overworld")
                  raycast/raycast-blocks (fn [& _] {:x 8 :y 9 :z 10 :face :up})
                  world-effects/find-entities-in-aabb (fn [& _] [])
                  entity/player-main-hand-placeable-block? (fn [_] true)
                  entity/player-creative? (fn [_] false)
                  entity/player-drop-main-hand-item-at! (fn [_ n x y z]
                                                          (swap! drop-calls* conj [n x y z])
                                                          true)
                  entity/player-place-main-hand-block-at-hit! (fn [& _]
                                                                {:placed? false
                                                                 :fallback-drop? true
                                                                 :pos {:x 8 :y 9 :z 10}
                                                                 :face :up})
                  entity/player-consume-main-hand-item! (fn [& _]
                                                         (swap! consume-calls* inc)
                                                         true)
                  helper/teleport-to! (fn [& _] (swap! teleport-calls* inc) true)
                  helper/deal-magic-damage! (fn [& _] {:critical? false})
                  skill-effects/add-skill-exp! (fn [& _] nil)
                  skill-effects/set-main-cooldown! (fn [& _] nil)
                  ctx/ctx-send-to-client! (fn [& _] nil)]
      (binding [raycast/*raycast* :mock
            world-effects/*world-effects* :mock]
        (shift/shift-tp-up! {:player-id "p1" :ctx-id "ctx-3" :player :player :cost-ok? true})))

    (is (= 1 @teleport-calls*))
    (is (= [[1 8.5 10.0 10.5]] @drop-calls*))
    (is (= 0 @consume-calls*))))

(deftest shift-tp-up-invalid-main-hand-skips-execution-test
  (let [teleport-calls* (atom 0)]
    (with-redefs [helper/skill-exp (fn [_ _] 0.5)
                  helper/cfg-lerp (fn [_ _ _] 20.0)
                  helper/player-position (fn [_] {:x 1.0 :y 2.0 :z 3.0})
                  helper/player-look-vec (fn [_] {:x 0.0 :y 0.0 :z 1.0})
                  helper/cfg-double (fn [_ _] 1.6)
                  geom/world-id-of (fn [_] "minecraft:overworld")
                  raycast/raycast-blocks (fn [& _] {:x 8 :y 9 :z 10 :face :up})
                  world-effects/find-entities-in-aabb (fn [& _] [])
                  entity/player-main-hand-placeable-block? (fn [_] false)
                  helper/teleport-to! (fn [& _] (swap! teleport-calls* inc) true)
                  ctx/ctx-send-to-client! (fn [& _] nil)]
      (binding [raycast/*raycast* :mock
                world-effects/*world-effects* :mock]
        (shift/shift-tp-up! {:player-id "p1" :ctx-id "ctx-4" :player :player :cost-ok? true})))

    (is (= 0 @teleport-calls*))))

(deftest shift-tp-down-respects-cost-gate-test
  (let [updates* (atom [])]
    (with-redefs [ctx/update-context! (fn [ctx-id f & args]
                                        (swap! updates* conj [ctx-id f args])
                                        nil)]
      (shift/shift-tp-down! {:ctx-id "ctx-cost-fail" :cost-ok? false})
      (shift/shift-tp-down! {:ctx-id "ctx-cost-ok" :cost-ok? true}))

    (is (= 1 (count @updates*)))
    (is (= "ctx-cost-ok" (ffirst @updates*)))
    (is (= assoc (second (first @updates*))))))

(deftest shift-tp-tick-invalid-main-hand-clears-trace-and-skips-fx-test
  (let [updates* (atom [])
        fx-calls* (atom 0)]
    (with-redefs [entity/player-main-hand-placeable-block? (fn [_] false)
                  ctx/update-context! (fn [ctx-id f & args]
                                        (swap! updates* conj [ctx-id f args])
                                        nil)
                  ctx/ctx-send-to-client! (fn [& _] (swap! fx-calls* inc) nil)]
      (shift/shift-tp-tick! {:player-id "p1" :player :player :ctx-id "ctx-tick" :hold-ticks 9}))

    (is (= 1 (count @updates*)))
    (is (= 0 @fx-calls*))
    (let [[_ _ args] (first @updates*)
          [_ skill-state] args]
      (is (= {:hold-ticks 9 :hand-valid? false :trace nil} skill-state)))))

(deftest shift-tp-up-creative-mode-skips-consume-test
  (let [consume-calls* (atom 0)
        teleport-calls* (atom 0)]
    (with-redefs [helper/skill-exp (fn [_ _] 0.5)
                  helper/cfg-lerp (fn [_ field _]
                                    (case field
                                      :targeting.range 25.0
                                      :combat.damage 10.0
                                      :cost.up.cp 260.0
                                      :cost.up.overload 40.0
                                      :cooldown.ticks 20.0
                                      0.0))
                  helper/cfg-double (fn [_ field]
                                      (case field
                                        :targeting.eye-height 1.6
                                        :progression.exp-base 0.002
                                        0.0))
                  helper/cfg-lerp-int (fn [& _] 20)
                  helper/player-position (fn [_] {:x 1.0 :y 64.0 :z 3.0})
                  helper/player-look-vec (fn [_] {:x 0.0 :y 0.0 :z 1.0})
                  geom/world-id-of (fn [_] "minecraft:overworld")
                  raycast/raycast-blocks (fn [& _] {:x 8 :y 64 :z 10 :face :up})
                  world-effects/find-entities-in-aabb (fn [& _] [])
                  entity/player-main-hand-placeable-block? (fn [_] true)
                  entity/player-creative? (fn [_] true)
                  entity/player-drop-main-hand-item-at! (fn [& _] false)
                  entity/player-place-main-hand-block-at-hit! (fn [& _]
                                                                {:placed? true
                                                                 :fallback-drop? false
                                                                 :pos {:x 8 :y 65 :z 10}
                                                                 :face :up})
                  entity/player-consume-main-hand-item! (fn [& _]
                                                         (swap! consume-calls* inc)
                                                         true)
                  helper/teleport-to! (fn [& _] (swap! teleport-calls* inc) true)
                  helper/deal-magic-damage! (fn [& _] {:critical? false})
                  skill-effects/add-skill-exp! (fn [& _] nil)
                  skill-effects/set-main-cooldown! (fn [& _] nil)
                  ctx/ctx-send-to-client! (fn [& _] nil)]
      (binding [raycast/*raycast* :mock
                world-effects/*world-effects* :mock]
        (shift/shift-tp-up! {:player-id "p1" :ctx-id "ctx-creative" :player :player :cost-ok? true})))

    (is (= 1 @teleport-calls*))
    (is (= 0 @consume-calls*))))

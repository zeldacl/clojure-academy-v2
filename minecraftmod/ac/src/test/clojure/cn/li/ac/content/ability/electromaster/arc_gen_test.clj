(ns cn.li.ac.content.ability.electromaster.arc-gen-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.fx :as fx]
            [cn.li.ac.content.ability.electromaster.arc-gen :as arc]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.effects.geom :as geom]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.block-manipulation :as block-manip]
            [cn.li.mcmod.platform.item :as pitem]
            [cn.li.mcmod.platform.potion-effects :as potion-effects]))

(defn- stub-lerp [_skill-id field-id exp]
  (case field-id
    :combat.damage (+ 5.0 (* 4.0 exp))
    :targeting.range (+ 6.0 (* 9.0 exp))
    :effect.ignite-probability (* 0.6 exp)
    :cost.down.cp (+ 30.0 (* 40.0 exp))
    :cost.down.overload (- 18.0 (* 7.0 exp))
    0.0))

(defn- stub-double [_skill-id field-id]
  (case field-id
    :effect.fishing-exp-threshold 0.5
    :effect.stun-exp-threshold 1.0
    0.0))

(defn- stub-double-list [_skill-id field-id]
  (case field-id
    :progression.exp-entity [0.0048 0.0024]
    :progression.exp-block [0.0018 0.0009]
    [0.0 0.0]))

(deftest miss-does-not-grant-exp-test
  (let [exp-calls* (atom [])
        fx-calls* (atom [])]
    (with-redefs [raycast/available? (constantly true)
                  entity-damage/available? (constantly true)
                  block-manip/available? (constantly true)
                  potion-effects/available? (constantly true)
                  geom/world-id-of (fn [_] "w")
                  geom/eye-pos (fn [_] {:x 0.0 :y 64.0 :z 0.0})
                  raycast/get-player-look-vector* (fn [_] {:x 0.0 :y 0.0 :z 1.0})
                  raycast/raycast-combined* (fn [& _] nil)
                  skill-effects/skill-exp (fn [& _] 0.25)
                  skill-effects/add-skill-exp! (fn [& args] (swap! exp-calls* conj args) nil)
                  skill-config/lerp-double stub-lerp
                  skill-config/tunable-double stub-double
                  skill-config/tunable-double-list stub-double-list
                  skill-config/probability (fn [& _] 0.0)
                  fx/send! (fn [ctx-id entry _evt payload]
                             (swap! fx-calls* conj [ctx-id (:topic entry) payload])
                             nil)]
      (arc/arc-gen-perform! {:player-id "p1" :ctx-id "ctx-1" :player {:id "player-obj"}})
      (is (empty? @exp-calls*))
      (is (= 1 (count @fx-calls*)))
      (is (= :arc-gen/fx-perform (second (first @fx-calls*)))))))

(deftest water-hit-rewards-fish-and-skips-ignite-test
  (let [ignite-calls* (atom [])
        fish-give* (atom [])
        exp-calls* (atom [])]
    (with-redefs [raycast/available? (constantly true)
                  entity-damage/available? (constantly true)
                  block-manip/available? (constantly true)
                  potion-effects/available? (constantly true)
                  geom/world-id-of (fn [_] "w")
                  geom/eye-pos (fn [_] {:x 1.0 :y 64.0 :z 1.0})
                  raycast/get-player-look-vector* (fn [_] {:x 0.0 :y 0.0 :z 1.0})
                  raycast/raycast-combined* (fn [& _]
                                             {:type :block
                                              :x 1.0 :y 64.0 :z 6.0
                                              :block-x 1 :block-y 64 :block-z 6})
                  block-manip/liquid-block?* (fn [& _] true)
                  block-manip/set-block!* (fn [& args] (swap! ignite-calls* conj args) nil)
                  pitem/create-item-stack-by-id (fn [_ _] {:item-id "minecraft:cooked_cod" :count 1})
                  entity/player-give-item-stack! (fn [player stack]
                                                   (swap! fish-give* conj [player stack])
                                                   true)
                  skill-effects/skill-exp (fn [& _] 0.75)
                  skill-effects/add-skill-exp! (fn [& args] (swap! exp-calls* conj args) nil)
                  skill-config/lerp-double stub-lerp
                  skill-config/tunable-double stub-double
                  skill-config/tunable-double-list stub-double-list
                  skill-config/probability (fn [& _] 1.0)
                  fx/send! (fn [& _] nil)]
      (arc/arc-gen-perform! {:player-id "p2" :ctx-id "ctx-2" :player {:id "player-obj"}})
      (is (= 1 (count @fish-give*)))
      (is (empty? @ignite-calls*))
      (is (= 1 (count @exp-calls*))))))

(deftest entity-hit-at-max-exp-applies-stun-test
  (let [potion-calls* (atom [])]
    (with-redefs [raycast/available? (constantly true)
                  entity-damage/available? (constantly true)
                  block-manip/available? (constantly true)
                  potion-effects/available? (constantly true)
                  geom/world-id-of (fn [_] "w")
                  geom/eye-pos (fn [_] {:x 0.0 :y 64.0 :z 0.0})
                  raycast/get-player-look-vector* (fn [_] {:x 0.0 :y 0.0 :z 1.0})
                  raycast/raycast-combined* (fn [& _]
                                             {:type :entity
                                              :x 0.0 :y 64.0 :z 5.0
                                              :entity-uuid "mob-1"})
                  entity-damage/apply-direct-damage!* (fn [& _] true)
                  potion-effects/apply-potion-effect!* (fn [& args]
                                                        (swap! potion-calls* conj args)
                                                        nil)
                  skill-effects/skill-exp (fn [& _] 1.0)
                  skill-effects/add-skill-exp! (fn [& _] nil)
                  skill-config/lerp-double stub-lerp
                  skill-config/tunable-double stub-double
                  skill-config/tunable-double-list stub-double-list
                  skill-config/probability (fn [& _] 0.0)
                  fx/send! (fn [& _] nil)]
      (arc/arc-gen-perform! {:player-id "p3" :ctx-id "ctx-3" :player {:id "player-obj"}})
      (is (= 2 (count @potion-calls*)))
      (is (= #{:slowness :weakness}
              (set (map #(nth % 1) @potion-calls*)))))))

(deftest miss-range-drives-fx-end-and-no-entity-arc-spawn-test
  (let [fx-calls* (atom [])
        spawn-calls* (atom [])]
    (with-redefs [raycast/available? (constantly true)
                  entity-damage/available? (constantly true)
                  block-manip/available? (constantly true)
                  potion-effects/available? (constantly true)
                  geom/world-id-of (fn [_] "w")
                  geom/eye-pos (fn [_] {:x 10.0 :y 64.0 :z 20.0})
                  raycast/get-player-look-vector* (fn [_] {:x 0.0 :y 0.0 :z 1.0})
                  raycast/raycast-combined* (fn [& _] nil)
                  skill-effects/skill-exp (fn [& _] 1.0)
                  skill-effects/add-skill-exp! (fn [& _] nil)
                  skill-config/lerp-double stub-lerp
                  skill-config/tunable-double stub-double
                  skill-config/tunable-double-list stub-double-list
                  skill-config/probability (fn [& _] 0.0)
                  entity/player-spawn-entity-by-id! (fn [& args]
                                                      (swap! spawn-calls* conj args)
                                                      true)
                  fx/send! (fn [ctx-id entry _evt payload]
                             (swap! fx-calls* conj [ctx-id (:topic entry) payload])
                             nil)]
      (arc/arc-gen-perform! {:player-id "p4" :ctx-id "ctx-4" :player {:id "player-obj"}})
      (is (empty? @spawn-calls*))
      (is (= 1 (count @fx-calls*)))
      (let [[_ _ payload] (first @fx-calls*)]
        (is (= {:x 10.0 :y 64.0 :z 20.0} (:start payload)))
        (is (= {:x 10.0 :y 64.0 :z 35.0} (:end payload)))))))

(ns cn.li.ac.content.ability.meltdowner.ray-barrage-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.test.skill-callback-test-helpers :as cb]
            [cn.li.ac.ability.fx :as fx]
            [cn.li.ac.content.ability.meltdowner.ray-barrage :as rb]
            [cn.li.ac.content.ability.meltdowner.damage-helper :as md-damage]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.effects.beam :as beam]
            [cn.li.ac.ability.effects.geom :as geom]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.effects.raycast :as raycast]
            [cn.li.ac.ability.effects.world :as world-effects]))

(defn- stub-lerp-double [_skill-id field-id _exp]
  (case field-id
    :combat.damage.plain 25.0
    :combat.damage.scattered 10.0
    :cost.down.cp 300.0
    :cost.down.overload 130.0
    :cooldown.ticks 100.0
    0.0))

(defn- stub-tunable-double [_skill-id field-id]
  (case field-id
    :targeting.range 22.0
    :scatter.target-radius 8.0
    :beam.radius 0.3
    :beam.query-radius 20.0
    :beam.step 0.8
    :beam.max-distance 22.0
    :beam.visual-distance 20.0
    :progression.exp-hit 0.003
    0.0))

(defn- stub-tunable-int [_skill-id field-id]
  (case field-id
    :scatter.count 3
    0))

(defn- reset-runtime-fixture [f]
  (rb/reset-ray-barrage-state-for-test!)
  (try
    (f)
    (finally
      (rb/reset-ray-barrage-state-for-test!))))

(use-fixtures :each reset-runtime-fixture)

(defn- capture-fx-topic! [fx*]
  (fn [_ entry _evt _payload]
    (swap! fx* conj (:topic entry))
    nil))

(deftest ray-barrage-perform-direct-branch-grants-exp-test
  (let [run-calls* (atom 0)
        exp-calls* (atom [])
        marks* (atom [])
        fx* (atom [])]
    (with-redefs [skill-effects/skill-exp (fn [& _] 0.0)
                  skill-config/lerp-double stub-lerp-double
                  skill-config/tunable-double stub-tunable-double
                  skill-config/tunable-int stub-tunable-int
                  geom/world-id-of (fn [_] "w")
                  geom/eye-pos (fn [_] {:x 0.0 :y 64.0 :z 0.0})
                  fx/send! (capture-fx-topic! fx*)
                  world-effects/available? (constantly true)
                  world-effects/find-entities-in-radius (fn [& _] [])
                  raycast/available? (constantly true)
                  raycast/raycast-combined (fn [& _]
                                              {:hit-type :entity
                                               :uuid "enemy-1"
                                               :type "entity.my_mod.enemy"})
                  raycast/player-look-vector (fn [_] {:x 0.0 :y 0.0 :z 1.0})
                  beam/execute-beam! (fn [_ _]
                                       (swap! run-calls* inc)
                                       {:beam-result {:performed? true
                                                      :hit-uuids ["target-ray"]}})
                  md-damage/mark-target! (fn [player-id target-id fx-context]
                                           (swap! marks* conj [player-id target-id fx-context])
                                           true)
                  skill-effects/add-skill-exp! (fn [& args]
                                                 (swap! exp-calls* conj args)
                                                 nil)]
      (cb/apply-invoke rb/ray-barrage-perform! :player-id "p1" :ctx-id "ctx-1"))

    (is (= 1 @run-calls*))
    (is (= [["p1" :ray-barrage 0.003]] @exp-calls*))
    (is (= 2 (count (filter #{:ray-barrage/fx-preray} @fx*))))
    (is (empty? (filter #{:ray-barrage/fx-barrage} @fx*)))
    (is (= 1 (count @marks*)))
    (is (= ["p1" "target-ray" {:ctx-id "ctx-1"}] (first @marks*)))))

(deftest ray-barrage-perform-silbarn-scatter-branch-test
  (let [run-calls* (atom [])
        exp-calls* (atom [])
        fx* (atom [])]
    (with-redefs [skill-effects/skill-exp (fn [& _] 0.0)
                  skill-config/lerp-double stub-lerp-double
                  skill-config/tunable-double stub-tunable-double
                  skill-config/tunable-int stub-tunable-int
                  geom/world-id-of (fn [_] "w")
                  geom/eye-pos (fn [_] {:x 0.0 :y 64.0 :z 0.0})
                  fx/send! (capture-fx-topic! fx*)
                  world-effects/available? (constantly true)
                  world-effects/find-entities-in-radius (fn [& _]
                                                         [{:uuid "enemy-a" :x 1.0 :y 64.0 :z 5.0 :eye-height 1.6}
                                                          {:uuid "enemy-b" :x -1.0 :y 64.0 :z 5.0 :eye-height 1.6}])
                  raycast/available? (constantly true)
                  raycast/raycast-combined (fn [& _]
                                              {:hit-type :entity
                                               :uuid "silbarn-1"
                                               :type "entity.my_mod.silbarn"})
                  raycast/player-look-vector (fn [_] {:x 0.0 :y 0.0 :z 1.0})
                  beam/execute-beam! (fn [_ spec]
                                       (swap! run-calls* conj (:damage spec))
                                       {:beam-result {:performed? true
                                                      :hit-uuids ["enemy-a"]}})
                  skill-effects/add-skill-exp! (fn [& args]
                                                 (swap! exp-calls* conj args)
                                                 nil)
                  md-damage/mark-target! (fn [& _] true)]
      (cb/apply-invoke rb/ray-barrage-perform! :player-id "p1" :ctx-id "ctx-scatter"))

    (is (= [10.0 10.0 10.0] @run-calls*))
    (is (= 2 (count (filter #{:ray-barrage/fx-preray} @fx*))))
    (is (= 2 (count (filter #{:ray-barrage/fx-barrage} @fx*))))
    (is (= [["p1" :ray-barrage 0.003]] @exp-calls*))))

(deftest ray-barrage-perform-locked-silbarn-falls-back-to-direct-test
  (let [run-calls* (atom 0)
        exp-calls* (atom [])
        fx* (atom [])
        call-idx* (atom 0)]
    (with-redefs [skill-effects/skill-exp (fn [& _] 0.0)
                  skill-config/lerp-double stub-lerp-double
                  skill-config/tunable-double stub-tunable-double
                  skill-config/tunable-int stub-tunable-int
                  geom/world-id-of (fn [_] "w")
                  geom/eye-pos (fn [_] {:x 0.0 :y 64.0 :z 0.0})
                  fx/send! (capture-fx-topic! fx*)
                  world-effects/available? (constantly true)
                  world-effects/find-entities-in-radius (fn [& _] [{:uuid "enemy" :x 0.0 :y 64.0 :z 6.0 :eye-height 1.6}])
                  raycast/available? (constantly true)
                  raycast/raycast-combined (fn [& _]
                                              (if (= 1 (swap! call-idx* inc))
                                                {:hit-type :entity
                                                 :uuid "silbarn-1"
                                                 :type "entity.my_mod.silbarn"
                                                 :is-hit false}
                                                {:hit-type :entity
                                                 :uuid "silbarn-1"
                                                 :type "entity.my_mod.silbarn"
                                                 :is-hit true}))
                  raycast/player-look-vector (fn [_] {:x 0.0 :y 0.0 :z 1.0})
                  beam/execute-beam! (fn [_ _]
                                       (swap! run-calls* inc)
                                       {:beam-result {:performed? true
                                                      :hit-uuids ["enemy"]}})
                  md-damage/mark-target! (fn [& _] true)
                  skill-effects/add-skill-exp! (fn [& args]
                                                 (swap! exp-calls* conj args)
                                                 nil)]
      (cb/apply-invoke rb/ray-barrage-perform! :player-id "p1" :ctx-id "ctx-2")
      (cb/apply-invoke rb/ray-barrage-perform! :player-id "p1" :ctx-id "ctx-2"))

    (is (= 4 @run-calls*))
    (is (= 4 (count (filter #{:ray-barrage/fx-preray} @fx*))))
    (is (= 2 (count (filter #{:ray-barrage/fx-barrage} @fx*))))
    (is (= 2 (count @exp-calls*)))))

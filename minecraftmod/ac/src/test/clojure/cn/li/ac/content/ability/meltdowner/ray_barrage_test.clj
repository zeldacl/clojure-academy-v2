(ns cn.li.ac.content.ability.meltdowner.ray-barrage-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.content.ability.meltdowner.ray-barrage :as rb]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.server.effect.core :as effect]
            [cn.li.ac.ability.server.effect.geom :as geom]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.mcmod.platform.raycast :as raycast]))

(defn- stub-lerp-double [_skill-id field-id _exp]
  (case field-id
    :combat.damage 6.0
    :cost.down.cp 300.0
    :cost.down.overload 130.0
    0.0))

(defn- stub-tunable-double [_skill-id field-id]
  (case field-id
    :beam.spread 0.18
    :beam.radius 0.3
    :beam.query-radius 20.0
    :beam.step 0.8
    :beam.max-distance 22.0
    :beam.visual-distance 20.0
    :progression.exp-hit 0.003
    0.0))

(defn- stub-tunable-int [_skill-id field-id]
  (case field-id
    :beam.count 5
    0))

(deftest ray-barrage-perform-hits-grant-exp-test
  (let [run-calls* (atom 0)
        exp-calls* (atom [])]
    (with-redefs [skill-effects/skill-exp (fn [& _] 0.0)
                  skill-config/lerp-double stub-lerp-double
                  skill-config/tunable-double stub-tunable-double
                  skill-config/tunable-int stub-tunable-int
                  geom/world-id-of (fn [_] "w")
                  geom/eye-pos (fn [_] {:x 0.0 :y 64.0 :z 0.0})
                  effect/run-op! (fn [_ _]
                                   (swap! run-calls* inc)
                                   {:beam-result {:performed? true}})
                  skill-effects/add-skill-exp! (fn [& args]
                                                 (swap! exp-calls* conj args)
                                                 nil)]
      (binding [raycast/*raycast* (reify raycast/IRaycast
                                    (raycast-blocks [_ _ _ _ _ _ _ _ _] nil)
                                    (raycast-entities [_ _ _ _ _ _ _ _ _] nil)
                                    (raycast-combined [_ _ _ _ _ _ _ _ _] nil)
                                    (get-player-look-vector [_ _] {:x 0.0 :y 0.0 :z 1.0})
                                    (raycast-from-player [_ _ _ _] nil))]
        (rb/ray-barrage-perform! {:player-id "p1" :ctx-id "ctx-1"})))

    (is (= 5 @run-calls*))
    (is (= [["p1" :ray-barrage 0.003]] @exp-calls*))))

(deftest ray-barrage-perform-without-hit-has-no-exp-test
  (let [run-calls* (atom 0)
        exp-calls* (atom [])]
    (with-redefs [skill-effects/skill-exp (fn [& _] 0.0)
                  skill-config/lerp-double stub-lerp-double
                  skill-config/tunable-double stub-tunable-double
                  skill-config/tunable-int stub-tunable-int
                  geom/world-id-of (fn [_] "w")
                  geom/eye-pos (fn [_] {:x 0.0 :y 64.0 :z 0.0})
                  effect/run-op! (fn [_ _]
                                   (swap! run-calls* inc)
                                   {:beam-result {:performed? false}})
                  skill-effects/add-skill-exp! (fn [& args]
                                                 (swap! exp-calls* conj args)
                                                 nil)]
      (binding [raycast/*raycast* (reify raycast/IRaycast
                                    (raycast-blocks [_ _ _ _ _ _ _ _ _] nil)
                                    (raycast-entities [_ _ _ _ _ _ _ _ _] nil)
                                    (raycast-combined [_ _ _ _ _ _ _ _ _] nil)
                                    (get-player-look-vector [_ _] {:x 0.0 :y 0.0 :z 1.0})
                                    (raycast-from-player [_ _ _ _] nil))]
        (rb/ray-barrage-perform! {:player-id "p1" :ctx-id "ctx-2"})))

    (is (= 5 @run-calls*))
    (is (empty? @exp-calls*))))

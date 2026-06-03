(ns cn.li.ac.content.ability.vecmanip.groundshock-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.content.ability.vecmanip.groundshock :as gs]
            [cn.li.ac.ability.fx :as fx]
            [cn.li.ac.test.support.fx-mocks :as fx-mocks]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.entity-motion :as entity-motion]
            [cn.li.mcmod.platform.player-motion :as player-motion]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.teleportation :as teleportation]))

(deftest horizontal-look-fallback-toggle-test
  (testing "fallback disabled returns nil when no horizontal look vector is available"
    (with-redefs [skill-config/tunable-boolean (fn [_ _] false)
                  raycast/available? (constantly false)]
      (is (nil? (@#'cn.li.ac.content.ability.vecmanip.groundshock/horizontal-look-with-fallback "p1")))))
  (testing "fallback enabled returns +Z direction when no horizontal look vector is available"
    (with-redefs [skill-config/tunable-boolean (fn [_ _] true)
                  raycast/available? (constantly false)]
      (is (= {:x 0.0 :y 0.0 :z 1.0}
             (@#'cn.li.ac.content.ability.vecmanip.groundshock/horizontal-look-with-fallback "p1"))))))

(deftest get-player-position-no-default-fallback-test
  (testing "returns nil when teleportation runtime is unavailable"
    (is (nil? (@#'cn.li.ac.content.ability.vecmanip.groundshock/get-player-position "p1"))))
  (testing "reads position from teleportation protocol when available"
    (with-redefs [teleportation/available? (constantly true)
                  teleportation/get-player-position* (fn [impl-player-id]
                                                       {:world-id "w" :x 1.0 :y 2.0 :z 3.0 :player impl-player-id})]
      (is (= {:world-id "w" :x 1.0 :y 2.0 :z 3.0 :player "p1"}
             (@#'cn.li.ac.content.ability.vecmanip.groundshock/get-player-position "p1"))))))

(deftest affect-entities-living-only-test
  (let [damage-calls* (atom [])
        velocity-calls* (atom [])
        exp-calls* (atom [])
        affected* (atom #{})]
    (with-redefs [entity-damage/available? (constantly true)
                  entity-damage/apply-direct-damage!* (fn [world-id entity-id damage _]
                                                       (swap! damage-calls* conj [world-id entity-id damage]))
                  entity-motion/available? (constantly true)
                  entity-motion/add-velocity!* (fn [world-id entity-id vx vy vz]
                                                 (swap! velocity-calls* conj [world-id entity-id vx vy vz]))
                  skill-effects/add-skill-exp! (fn [& args] (swap! exp-calls* conj args) nil)
                  skill-config/tunable-double (fn [_ field-id]
                                                (case field-id
                                                  :combat.entity-search-radius 2.0
                                                  :progression.exp-entity 0.002
                                                  0.0))]
      (@#'cn.li.ac.content.ability.vecmanip.groundshock/affect-entities!
       "player" "w" 0 64 0 5.0 0.8
       [{:uuid "living-1" :living? true :x 0.5 :y 64.0 :z 0.5 :width 0.6 :height 1.8}
        {:uuid "item-1" :living? false :x 0.5 :y 64.0 :z 0.5 :width 0.25 :height 0.25}
        {:uuid "player" :living? true :x 0.5 :y 64.0 :z 0.5 :width 0.6 :height 1.8}]
       affected*)
      (is (= #{"living-1"} @affected*))
      (is (= [["w" "living-1" 5.0]] @damage-calls*))
      (is (= [["w" "living-1" 0.0 0.8 0.0]] @velocity-calls*))
      (is (= 1 (count @exp-calls*))))))

(deftest key-up-missing-position-sends-fx-end-test
  (let [{:keys [calls* send!]} (fx-mocks/capture-fx-send!)]
    (with-redefs [ctx/get-context (fn [_] {:skill-state {:charge-ticks 10 :performed? false}})
                  skill-config/tunable-int (fn [_ field-id]
                                             (case field-id
                                               :charge.min-ticks 5
                                               0))
                  skill-effects/skill-exp (fn [_ _] 0.5)
                  teleportation/available? (constantly false)
                  raycast/available? (constantly true)
                  raycast/get-player-look-vector* (fn [_] {:x 0.0 :y 0.0 :z 1.0})
                  fx/send! send!
                  player-motion/available? (constantly true)
                  player-motion/is-on-ground?* (constantly true)]
      (gs/groundshock-on-key-up {:player-id "p1" :ctx-id "ctx-1" :cost-ok? true}))
    (is (= [["ctx-1" :groundshock/fx-end :end {:performed? false}]] @calls*))))

(deftest key-up-cost-fail-sends-fx-end-test
  (let [{:keys [calls* send!]} (fx-mocks/capture-fx-send!)]
    (with-redefs [ctx/get-context (fn [_] {:skill-state {:charge-ticks 10 :performed? false}})
                  skill-config/tunable-int (fn [_ field-id]
                                             (case field-id
                                               :charge.min-ticks 5
                                               0))
                  skill-effects/skill-exp (fn [_ _] 0.5)
                  fx/send! send!
                  player-motion/available? (constantly true)
                  player-motion/is-on-ground?* (constantly true)]
      (gs/groundshock-on-key-up {:player-id "p1" :ctx-id "ctx-2" :cost-ok? false}))
    (is (= [["ctx-2" :groundshock/fx-end :end {:performed? false}]] @calls*))))

(deftest key-up-missing-direction-sends-fx-end-test
  (let [{:keys [calls* send!]} (fx-mocks/capture-fx-send!)]
    (with-redefs [ctx/get-context (fn [_] {:skill-state {:charge-ticks 10 :performed? false}})
                  skill-config/tunable-int (fn [_ field-id]
                                             (case field-id
                                               :charge.min-ticks 5
                                               0))
                  skill-config/tunable-boolean (fn [_ _] false)
                  skill-effects/skill-exp (fn [_ _] 0.5)
                  teleportation/available? (constantly true)
                  teleportation/get-player-position* (fn [_] {:world-id "w" :x 0.0 :y 64.0 :z 0.0})
                  raycast/available? (constantly true)
                  raycast/get-player-look-vector* (fn [_] {:x 0.0 :y 1.0 :z 0.0})
                  fx/send! send!
                  player-motion/available? (constantly true)
                  player-motion/is-on-ground?* (constantly true)]
      (gs/groundshock-on-key-up {:player-id "p1" :ctx-id "ctx-3" :cost-ok? true}))
    (is (= [["ctx-3" :groundshock/fx-end :end {:performed? false}]] @calls*))))

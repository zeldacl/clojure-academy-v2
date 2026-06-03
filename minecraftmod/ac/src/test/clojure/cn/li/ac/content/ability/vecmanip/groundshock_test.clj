(ns cn.li.ac.content.ability.vecmanip.groundshock-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.content.ability.vecmanip.groundshock :as gs]
            [cn.li.ac.content.ability.fx-helpers :as fx]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.entity-motion :as entity-motion]
            [cn.li.mcmod.platform.player-motion :as player-motion]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.teleportation :as teleportation]))

(deftest horizontal-look-fallback-toggle-test
  (testing "fallback disabled returns nil when no horizontal look vector is available"
    (with-redefs [gs/cfg-boolean (fn [_] false)]
        (is (nil? (@#'cn.li.ac.content.ability.vecmanip.groundshock/horizontal-look-with-fallback "p1"))))))

  (testing "fallback enabled returns +Z direction when no horizontal look vector is available"
    (with-redefs [gs/cfg-boolean (fn [_] true)]
        (is (= {:x 0.0 :y 0.0 :z 1.0}
               (@#'cn.li.ac.content.ability.vecmanip.groundshock/horizontal-look-with-fallback "p1")))))))

(deftest get-player-position-no-default-fallback-test
  (testing "returns nil when teleportation runtime is unavailable"
      (is (nil? (@#'cn.li.ac.content.ability.vecmanip.groundshock/get-player-position "p1")))))

  (testing "reads position from teleportation protocol when available"
    (with-redefs [teleportation/get-player-position* (fn [_ impl-player-id]
                                                      {:world-id "w" :x 1.0 :y 2.0 :z 3.0 :player impl-player-id})]
        (is (= {:world-id "w" :x 1.0 :y 2.0 :z 3.0 :player "p1"}
               (@#'cn.li.ac.content.ability.vecmanip.groundshock/get-player-position "p1")))))))

(deftest affect-entities-living-only-test
  (let [damage-calls* (atom [])
        velocity-calls* (atom [])
        exp-calls* (atom 0)
        affected* (atom #{})]
    (with-redefs [entity-damage/apply-direct-damage!* (fn [_ world-id entity-id damage _]
                                                      (swap! damage-calls* conj [world-id entity-id damage]))
                  entity-motion/add-velocity!* (fn [_ world-id entity-id vx vy vz]
                                                (swap! velocity-calls* conj [world-id entity-id vx vy vz]))
                  gs/add-exp! (fn [_ _] (swap! exp-calls* inc))
                  gs/cfg-double (fn [field]
                                  (case field
                                    :combat.entity-search-radius 2.0
                                    :progression.exp-entity 0.002
                                    0.0))]
        (@#'cn.li.ac.content.ability.vecmanip.groundshock/affect-entities!
         "player" "w" 0 64 0 5.0 0.8
         [{:uuid "living-1" :living? true :x 0.5 :y 64.0 :z 0.5 :width 0.6 :height 1.8}
          {:uuid "item-1" :living? false :x 0.5 :y 64.0 :z 0.5 :width 0.25 :height 0.25}
          {:uuid "player" :living? true :x 0.5 :y 64.0 :z 0.5 :width 0.6 :height 1.8}]
         affected*))

      (is (= #{"living-1"} @affected*))
      (is (= [["w" "living-1" 5.0]] @damage-calls*))
      (is (= [["w" "living-1" 0.0 0.8 0.0]] @velocity-calls*))
      (is (= 1 @exp-calls*)))))

(deftest key-up-missing-position-sends-fx-end-test
  (let [end-calls* (atom [])]
    (with-redefs [ctx/get-context (fn [_] {:skill-state {:charge-ticks 10 :performed? false}})
                  gs/cfg-int (fn [field]
                               (case field
                                 :charge.min-ticks 5
                                 0))
                  gs/skill-exp (fn [_] 0.5)
                  gs/get-player-position (fn [_] nil)
                  gs/horizontal-look-with-fallback (fn [_] {:x 0.0 :y 0.0 :z 1.0})
                  fx/send-end! (fn [ctx-id ch payload]
                                 (swap! end-calls* conj [ctx-id ch payload]))]
        (with-redefs [player-motion/is-on-ground?* (fn [_ _] true)]
          (gs/groundshock-on-key-up {:player-id "p1" :ctx-id "ctx-1" :cost-ok? true})))

      (is (= [["ctx-1" :groundshock/fx-end {:performed? false}]] @end-calls*)))))

(deftest key-up-cost-fail-sends-fx-end-test
  (let [end-calls* (atom [])]
    (with-redefs [ctx/get-context (fn [_] {:skill-state {:charge-ticks 10 :performed? false}})
                  gs/cfg-int (fn [field]
                               (case field
                                 :charge.min-ticks 5
                                 0))
                  gs/skill-exp (fn [_] 0.5)
                  fx/send-end! (fn [ctx-id ch payload]
                                 (swap! end-calls* conj [ctx-id ch payload]))]
        (with-redefs [player-motion/is-on-ground?* (fn [_ _] true)]
          (gs/groundshock-on-key-up {:player-id "p1" :ctx-id "ctx-2" :cost-ok? false})))

      (is (= [["ctx-2" :groundshock/fx-end {:performed? false}]] @end-calls*)))))

(deftest key-up-missing-direction-sends-fx-end-test
  (let [end-calls* (atom [])]
    (with-redefs [ctx/get-context (fn [_] {:skill-state {:charge-ticks 10 :performed? false}})
                  gs/cfg-int (fn [field]
                               (case field
                                 :charge.min-ticks 5
                                 0))
                  gs/skill-exp (fn [_] 0.5)
                  gs/get-player-position (fn [_] {:world-id "w" :x 0.0 :y 64.0 :z 0.0})
                  gs/horizontal-look-with-fallback (fn [_] nil)
                  fx/send-end! (fn [ctx-id ch payload]
                                 (swap! end-calls* conj [ctx-id ch payload]))]
        (with-redefs [player-motion/is-on-ground?* (fn [_ _] true)]
          (gs/groundshock-on-key-up {:player-id "p1" :ctx-id "ctx-3" :cost-ok? true})))

      (is (= [["ctx-3" :groundshock/fx-end {:performed? false}]] @end-calls*)))))

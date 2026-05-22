(ns cn.li.ac.content.ability.vecmanip.blood-retrograde-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.ability.service.dispatcher :as ctx]
            [cn.li.ac.ability.server.service.skill-effects :as skill-effects]
            [cn.li.ac.content.ability.fx-helpers :as fx]
            [cn.li.ac.content.ability.vecmanip.blood-retrograde :as br]
            [cn.li.ac.ability.server.effect.geom :as geom]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.raycast :as raycast]))

(defn- mock-cfg-int [field]
  (case field
    :charge.max-ticks 30
    0))

(defn- mock-cfg-double [field]
  (case field
    :targeting.distance 2.0
    :charge.fx-ratio-ticks 20.0
    :progression.exp-hit 0.002
    0.0))

(defn- mock-cfg-lerp [field _exp]
  (case field
    :cost.release.cp 280.0
    :cost.release.overload 55.0
    :combat.damage 30.0
    0.0))

(defn- mock-cfg-lerp-int [field _exp]
  (case field
    :cooldown.ticks 90
    0))

(defn- make-context-mocks [initial-ctx]
  (let [ctx-state (atom initial-ctx)
        terminate-calls (atom [])]
    {:ctx-state ctx-state
     :terminate-calls terminate-calls
     :get-context (fn [_] @ctx-state)
     :update-context! (fn [_ f & args]
                        (swap! ctx-state #(when % (apply f % args))))
     :terminate-context! (fn [ctx-id _]
                           (swap! terminate-calls conj ctx-id))}))

(deftest release-cost-tick-is-zero-even-when-hit-test
  (testing "tick release cost is disabled even if a target is available"
    (with-redefs [br/release-hit (fn [& _] {:entity-id "e-1"})
            br/skill-exp (fn [_] 0.5)]
      (is (= 0.0 ((get-in br/blood_retrograde [:cost :tick :cp])
                  {:player-id "p1" :ctx-id "ctx-1"})))
      (is (= 0.0 ((get-in br/blood_retrograde [:cost :tick :overload])
                  {:player-id "p1" :ctx-id "ctx-1"}))))))

(deftest release-cost-up-only-fires-on-hit-test
  (testing "up-stage release costs only apply when the release raycast hits"
    (with-redefs [br/release-hit (fn [& _] nil)
            br/skill-exp (fn [_] 0.0)]
      (is (= 0.0 ((get-in br/blood_retrograde [:cost :up :cp])
                  {:player-id "p1" :ctx-id "ctx-1"})))
      (is (= 0.0 ((get-in br/blood_retrograde [:cost :up :overload])
                  {:player-id "p1" :ctx-id "ctx-1"}))))
    (with-redefs [br/release-hit (fn [& _] {:entity-id "e-1"})
            br/skill-exp (fn [_] 0.0)]
      (is (= 280.0 ((get-in br/blood_retrograde [:cost :up :cp])
                    {:player-id "p1" :ctx-id "ctx-1"})))
      (is (= 55.0 ((get-in br/blood_retrograde [:cost :up :overload])
                   {:player-id "p1" :ctx-id "ctx-1"}))))))

(deftest auto-release-at-max-tick-applies-side-effects-test
  (let [{:keys [ctx-state get-context update-context! terminate-context! terminate-calls]}
        (make-context-mocks {:skill-state {:ticks 29
                                           :executed? false
                                           :ended? false}})
        damage-calls* (atom [])
        cooldown-calls* (atom [])
        exp-calls* (atom [])
        perform-calls* (atom [])
        end-calls* (atom [])]
    (with-redefs [br/cfg-int mock-cfg-int
            br/cfg-double mock-cfg-double
            br/cfg-lerp mock-cfg-lerp
            br/cfg-lerp-int mock-cfg-lerp-int
            br/skill-exp (fn [_] 0.0)
          br/get-player-look (fn [_] {:x 0.0 :y 0.0 :z 1.0})
                  ctx/get-context get-context
                  ctx/update-context! update-context!
                  ctx/terminate-context! terminate-context!
                  geom/world-id-of (fn [_] "w")
                  raycast/raycast-from-player (fn [& _]
                                                {:entity-id "target-1"
                                                 :x 1.0 :y 2.0 :z 3.0})
                  raycast/raycast-blocks (fn [& _]
                                           {:x 1.0 :y 2.0 :z 3.0 :face :up})
                  fx/send-update! (fn [& _] nil)
                  fx/send-perform! (fn [ctx-id ch payload]
                                     (swap! perform-calls* conj [ctx-id ch payload]))
                  fx/send-end! (fn [ctx-id ch payload]
                                 (swap! end-calls* conj [ctx-id ch payload]))
                  entity-damage/apply-direct-damage! (fn [_ world-id target-id damage kind]
                                                      (swap! damage-calls* conj [world-id target-id damage kind]))
                  skill-effects/set-main-cooldown! (fn [player-id skill-id ticks]
                                                     (swap! cooldown-calls* conj [player-id skill-id ticks]))
                  skill-effects/add-skill-exp! (fn [player-id skill-id amount]
                                                 (swap! exp-calls* conj [player-id skill-id amount]))]
      (binding [raycast/*raycast* :mock
                entity-damage/*entity-damage* :mock]
        (br/blood-retrograde-on-key-tick {:player-id "p1"
                                          :ctx-id "ctx-1"
                                          :cost-ok? true})))

    (is (= [["w" "target-1" 30.0 :generic]] @damage-calls*))
    (is (= [["p1" :blood-retrograde 90]] @cooldown-calls*))
    (is (= [["p1" :blood-retrograde 0.002]] @exp-calls*))
    (is (= 1 (count @perform-calls*)))
    (is (= 1 (count @end-calls*)))
    (is (= ["ctx-1"] @terminate-calls))
    (is (true? (get-in @ctx-state [:skill-state :executed?])))
    (is (true? (get-in @ctx-state [:skill-state :ended?])))
    (is (true? (get-in @ctx-state [:skill-state :performed?])))))

(deftest manual-release-without-hit-only-ends-context-test
  (let [{:keys [ctx-state get-context update-context! terminate-context! terminate-calls]}
        (make-context-mocks {:skill-state {:ticks 12
                                           :executed? false
                                           :ended? false}})
        damage-calls* (atom [])
        end-calls* (atom [])]
    (with-redefs [br/cfg-double mock-cfg-double
            br/cfg-lerp mock-cfg-lerp
            br/cfg-lerp-int mock-cfg-lerp-int
            br/skill-exp (fn [_] 0.0)
          br/get-player-look (fn [_] {:x 0.0 :y 0.0 :z 1.0})
                  ctx/get-context get-context
                  ctx/update-context! update-context!
                  ctx/terminate-context! terminate-context!
                  geom/world-id-of (fn [_] "w")
                  raycast/raycast-from-player (fn [& _] nil)
                  fx/send-update! (fn [& _] nil)
                  fx/send-perform! (fn [& _] nil)
                  fx/send-end! (fn [ctx-id ch payload]
                                 (swap! end-calls* conj [ctx-id ch payload]))
                  entity-damage/apply-direct-damage! (fn [& _]
                                                      (swap! damage-calls* conj :damage))]
      (binding [raycast/*raycast* :mock
                entity-damage/*entity-damage* :mock]
        (br/blood-retrograde-on-key-up {:player-id "p1"
                                        :ctx-id "ctx-2"
                                        :cost-ok? true})))

    (is (empty? @damage-calls*))
    (is (= [["ctx-2" :blood-retrograde/fx-end {:performed? false}]] @end-calls*))
    (is (= ["ctx-2"] @terminate-calls))
    (is (false? (get-in @ctx-state [:skill-state :performed?] false)))
    (is (true? (get-in @ctx-state [:skill-state :ended?])))))

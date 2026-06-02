(ns cn.li.ac.content.ability.teleporter.mark-teleport-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.context-skill-state :as ctx-skill]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.test.support.player-state :as ps-fix]
            [cn.li.ac.content.ability.teleporter.mark-teleport :as mark]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.teleportation :as teleportation]))

(use-fixtures :each ps-fix/clean-player-states-fixture)

(defn- make-context-mocks [initial]
  (let [ctx* (atom initial)]
    {:ctx* ctx*
     :get-context (fn [_] @ctx*)
     :update-skill-state-root! (fn [_ f & args]
                        (swap! ctx* update :skill-state (fn [ss] (apply f (or ss {}) args))))}))

(deftest mark-teleport-on-key-down-initializes-hold-state-test
  (let [{:keys [ctx* get-context update-skill-state-root!]}
        (make-context-mocks {:skill-state {:legacy true}})]
    (with-redefs [ctx/get-context get-context
                  ctx-skill/update-skill-state-root! update-skill-state-root!]
      (mark/mark-teleport-on-key-down {:ctx-id "ctx-1"}))

    (is (= {:hold-ticks 0 :has-target false}
           (:skill-state @ctx*)))))

(deftest mark-teleport-on-key-up-short-tap-success-sends-perform-and-applies-effects-test
  (let [{:keys [ctx* get-context update-skill-state-root!]}
        (make-context-mocks {:skill-state {:hold-ticks 0 :has-target false}})
        teleport-calls* (atom [])
        reset-calls* (atom [])
        fx-calls* (atom [])
        exp-calls* (atom [])
        cooldown-calls* (atom [])]
    (with-redefs [ctx/get-context get-context
                  ctx-skill/update-skill-state-root! update-skill-state-root!
                  ctx/ctx-send-to-client! (fn [ctx-id channel payload]
                                            (swap! fx-calls* conj [ctx-id channel payload])
                                            nil)
                  skill-effects/add-skill-exp! (fn [player-id skill-id amount]
                                                 (swap! exp-calls* conj [player-id skill-id amount])
                                                 nil)
                  skill-effects/set-main-cooldown! (fn [player-id skill-id ticks]
                                                     (swap! cooldown-calls* conj [player-id skill-id ticks])
                                                     nil)
                  skill-effects/skill-exp (fn [_ _] 0.5)
                  skill-effects/current-cp (fn [_] 1000.0)
                  entity/player-creative? (fn [_] false)
                  teleportation/get-player-position (fn [_ _]
                                                     {:world-id "minecraft:overworld"
                                                      :x 1.0 :y 64.0 :z 3.0})
                  teleportation/teleport-player! (fn [_ player-id world-id x y z]
                                                   (swap! teleport-calls* conj [player-id world-id x y z])
                                                   true)
                  teleportation/reset-fall-damage! (fn [_ player-id]
                                                     (swap! reset-calls* conj player-id)
                                                     true)
                  raycast/get-player-look-vector (fn [_ _] {:x 1.0 :y 0.0 :z 0.0})
                  raycast/raycast-combined (fn [& _]
                                            {:hit-type :entity
                                             :hit-x 10.0 :hit-y 62.4 :hit-z 12.0
                                             :eye-height 1.6})]
      (binding [teleportation/*teleportation* :mock-tp
                raycast/*raycast* :mock-raycast]
        (mark/mark-teleport-on-key-up {:player-id "p1"
                                       :ctx-id "ctx-2"
                                       :player :player
                                       :cost-ok? true})))

    (is (= 1 (count @teleport-calls*)))
    (is (= ["p1"] @reset-calls*))
    (is (= 1 (count @exp-calls*)))
    (is (= 1 (count @cooldown-calls*)))
    (is (= 1 (count @fx-calls*)))
    (is (= :mark-teleport/fx-perform (second (first @fx-calls*))))
    (is (map? (get-in (first @fx-calls*) [2 :target])))
    (is (= true (get-in @ctx* [:skill-state :has-target])))))

(deftest mark-teleport-on-key-up-cost-fail-has-no-side-effects-test
  (let [{:keys [ctx* get-context update-skill-state-root!]}
        (make-context-mocks {:skill-state {:hold-ticks 5
                                           :has-target true
                                           :world-id "minecraft:overworld"
                                           :target-x 7.0 :target-y 70.0 :target-z 9.0
                                           :distance 9.0 :exp 0.4}})
        teleport-calls* (atom 0)
        fx-calls* (atom 0)
        exp-calls* (atom 0)
        cooldown-calls* (atom 0)]
    (with-redefs [ctx/get-context get-context
                  ctx-skill/update-skill-state-root! update-skill-state-root!
                  ctx/ctx-send-to-client! (fn [& _] (swap! fx-calls* inc) nil)
                  skill-effects/add-skill-exp! (fn [& _] (swap! exp-calls* inc) nil)
                  skill-effects/set-main-cooldown! (fn [& _] (swap! cooldown-calls* inc) nil)
                  teleportation/get-player-position (fn [& _] nil)
                  teleportation/teleport-player! (fn [& _] (swap! teleport-calls* inc) true)]
      (binding [teleportation/*teleportation* :mock-tp
                raycast/*raycast* nil]
        (mark/mark-teleport-on-key-up {:player-id "p1"
                                       :ctx-id "ctx-3"
                                       :player :player
                                       :cost-ok? false})))

    (is (= 0 @teleport-calls*))
    (is (= 0 @fx-calls*))
    (is (= 0 @exp-calls*))
    (is (= 0 @cooldown-calls*))
    (is (= true (get-in @ctx* [:skill-state :has-target])))))

(deftest mark-teleport-on-key-up-min-distance-does-not-perform-test
  (let [{:keys [get-context update-skill-state-root!]}
        (make-context-mocks {:skill-state {:hold-ticks 2
                                           :has-target true
                                           :world-id "minecraft:overworld"
                                           :target-x 4.0 :target-y 65.0 :target-z 4.0
                                           :distance 2.5 :exp 0.4}})
        teleport-calls* (atom 0)
        fx-calls* (atom 0)]
    (with-redefs [ctx/get-context get-context
                  ctx-skill/update-skill-state-root! update-skill-state-root!
                  ctx/ctx-send-to-client! (fn [& _] (swap! fx-calls* inc) nil)
                  teleportation/get-player-position (fn [& _] nil)
                  teleportation/teleport-player! (fn [& _] (swap! teleport-calls* inc) true)
                  skill-effects/add-skill-exp! (fn [& _] nil)
                  skill-effects/set-main-cooldown! (fn [& _] nil)]
      (binding [teleportation/*teleportation* :mock-tp
                raycast/*raycast* nil]
        (mark/mark-teleport-on-key-up {:player-id "p1"
                                       :ctx-id "ctx-4"
                                       :player :player
                                       :cost-ok? true})))

    (is (= 0 @teleport-calls*))
    (is (= 0 @fx-calls*))))

(deftest mark-teleport-on-key-up-teleport-failure-does-not-send-perform-test
  (let [{:keys [get-context update-skill-state-root!]}
        (make-context-mocks {:skill-state {:hold-ticks 1
                                           :has-target true
                                           :world-id "minecraft:overworld"
                                           :target-x 10.0 :target-y 64.0 :target-z 12.0
                                           :distance 8.0 :exp 0.5}})
        fx-calls* (atom 0)
        exp-calls* (atom 0)
        cooldown-calls* (atom 0)]
    (with-redefs [ctx/get-context get-context
                  ctx-skill/update-skill-state-root! update-skill-state-root!
                  ctx/ctx-send-to-client! (fn [& _] (swap! fx-calls* inc) nil)
                  teleportation/get-player-position (fn [& _] nil)
                  teleportation/teleport-player! (fn [& _] false)
                  teleportation/reset-fall-damage! (fn [& _] true)
                  skill-effects/add-skill-exp! (fn [& _] (swap! exp-calls* inc) nil)
                  skill-effects/set-main-cooldown! (fn [& _] (swap! cooldown-calls* inc) nil)]
      (binding [teleportation/*teleportation* :mock-tp
                raycast/*raycast* nil]
        (mark/mark-teleport-on-key-up {:player-id "p1"
                                       :ctx-id "ctx-5"
                                       :player :player
                                       :cost-ok? true})))

    (is (= 0 @fx-calls*))
    (is (= 0 @exp-calls*))
    (is (= 0 @cooldown-calls*))))

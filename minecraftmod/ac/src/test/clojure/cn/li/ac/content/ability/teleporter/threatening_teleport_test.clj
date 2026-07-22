(ns cn.li.ac.content.ability.teleporter.threatening-teleport-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.test.skill-callback-test-helpers :as cb]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.achievement.dispatcher :as ach-dispatcher]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.context-skill-state :as ctx-skill]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.fx :as fx]
            [cn.li.ac.test.support.fx-mocks :as fx-mocks]
            [cn.li.ac.content.ability.teleporter.threatening-teleport :as tt]
            [cn.li.ac.content.ability.teleporter.tp-skill-helper :as helper]
            [cn.li.ac.ability.effects.geom :as geom]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.raycast :as raycast]))

(defn- make-context-mocks [initial-ctx]
  (let [ctx* (atom initial-ctx)]
    {:ctx* ctx*
     :get-context (fn [_] @ctx*)
     :update-skill-state-root! (fn [_ f & args]
                                 (swap! ctx* update :skill-state
                                        (fn [ss]
                                          (if (and (= f identity) (= 1 (count args)))
                                            (first args)
                                            (apply f (or ss {}) args)))))}))

(deftest threatening-tp-up-cost-fail-no-side-effects-test
  (let [{:keys [get-context]} (make-context-mocks {:skill-state {:trace {:world-id "w"
                                                                          :drop-x 1.0 :drop-y 2.0 :drop-z 3.0
                                                                          :attacked? true :target-uuid "enemy"}}})
        damage-calls* (atom 0)
        exp-calls* (atom 0)
        cooldown-calls* (atom 0)
        fx-calls* (atom 0)
        ach-calls* (atom 0)]
    (with-redefs [ctx/get-context get-context
                  helper/deal-magic-damage! (fn [& _] (swap! damage-calls* inc))
                  skill-effects/add-skill-exp! (fn [& _] (swap! exp-calls* inc))
                  skill-effects/set-main-cooldown! (fn [& _] (swap! cooldown-calls* inc))
                  fx/send! (fn [& _] (swap! fx-calls* inc))
                  ach-dispatcher/trigger-custom-event! (fn [& _] (swap! ach-calls* inc))]
      (cb/apply-invoke tt/threatening-tp-up! :player-id "p1" :ctx-id "ctx-1" :player-ref :player :cost-ok? false))

    (is (= 0 @damage-calls*))
    (is (= 0 @exp-calls*))
    (is (= 0 @cooldown-calls*))
    (is (= 0 @fx-calls*))
    (is (= 0 @ach-calls*))))

(deftest threatening-tp-tick-updates-trace-and-sends-update-fx-test
  (let [{:keys [ctx* get-context update-skill-state-root!]} (make-context-mocks {:skill-state {}})
        {:keys [calls* send!]} (fx-mocks/capture-fx-send!)]
    (with-redefs [ctx/get-context get-context
                  ctx-skill/update-skill-state-root! update-skill-state-root!
                  fx/send! send!
                  skill-effects/skill-exp (fn [_ _] 0.5)
                  skill-config/lerp-double (fn [_ _ _] 12.0)
                  helper/player-position (fn [_] {:x 1.0 :y 2.0 :z 3.0})
                  helper/player-look-vec (fn [_] {:x 0.0 :y 0.0 :z 1.0})
                  geom/world-id-of (fn [_] "minecraft:overworld")
                  raycast/available? (constantly true)
                  raycast/raycast-combined (fn [& _]
                                              {:hit-type :entity
                                               :entity-uuid "enemy"
                                               :hit-x 4.0 :hit-y 5.0 :hit-z 6.0
                                               :distance 7.0})]
      (cb/apply-invoke tt/threatening-tp-tick! :player-id "p1" :ctx-id "ctx-2" :hold-ticks 9))

    (is (= 9 (get-in @ctx* [:skill-state :hold-ticks])))
    (is (= true (get-in @ctx* [:skill-state :trace :attacked?])))
    (is (= "enemy" (get-in @ctx* [:skill-state :trace :target-uuid])))
    (is (= [["ctx-2" :threatening-teleport/fx-update :update
             {:start-x 1.0 :start-y 3.62 :start-z 3.0
              :drop-x 4.0 :drop-y 5.0 :drop-z 6.0
              :attacked? true
              :target-uuid "enemy"}]]
           @calls*))))

(deftest threatening-tp-up-hit-success-test
  (let [{:keys [get-context]} (make-context-mocks {:skill-state {:trace {:world-id "minecraft:overworld"
                                                                          :start-x 1.0 :start-y 2.0 :start-z 3.0
                                                                          :drop-x 4.0 :drop-y 5.0 :drop-z 6.0
                                                                          :attacked? true
                                                                          :target-uuid "enemy"}}})
        damage-calls* (atom [])
        exp-calls* (atom [])
        cooldown-calls* (atom [])
        fx-calls* (atom [])
        crit-fx-calls* (atom [])
        ach-calls* (atom [])
        consume-calls* (atom 0)
        drop-calls* (atom [])]
    (with-redefs [ctx/get-context get-context
                  skill-effects/skill-exp (fn [_ _] 0.5)
                  skill-config/lerp-double (fn [_ field _]
                                    (case field
                                      :combat.damage 4.0
                                      :targeting.range 10.0
                                      :cost.up.cp 60.0
                                      :cost.up.overload 12.0
                                      0.0))
                  skill-config/lerp-int (fn [_ _ _] 22)
                  skill-config/tunable-double (fn [_ field]
                                      (case field
                                        :progression.exp-base 0.003
                                        :progression.exp-hit-factor 1.0
                                        :progression.exp-miss-factor 0.2
                                        0.0))
                  skill-config/probability (fn [_ field]
                                           (case field
                                             :interaction.drop-prob.hit 0.3
                                             :interaction.drop-prob.miss 1.0
                                             0.0))
                  entity/player-get-main-hand-item-count (fn [_] 1)
                  entity/player-consume-main-hand-item! (fn [_ _]
                                                         (swap! consume-calls* inc)
                                                         true)
                  entity/player-drop-main-hand-item-at! (fn [_ amount x y z]
                                                          (swap! drop-calls* conj [amount x y z])
                                                          true)
                  helper/deal-magic-damage! (fn [_ world-id target-uuid damage]
                                              (swap! damage-calls* conj [world-id target-uuid damage])
                                              {:critical? true
                                               :crit-level 1
                                               :crit-rate 1.6
                                               :message-key "ability.teleporter.critical_hit"
                                               :message-args ["x1.6"]
                                               :damage-after damage
                                               :applied? true})
                  skill-effects/add-skill-exp! (fn [player-id skill-id amount]
                                                 (swap! exp-calls* conj [player-id skill-id amount]))
                  skill-effects/set-main-cooldown! (fn [player-id skill-id ticks]
                                                     (swap! cooldown-calls* conj [player-id skill-id ticks]))
                  fx/send! (fn [ctx-id entry _evt payload]
                             (swap! fx-calls* conj [(:topic entry) payload])
                             (when (= (:topic entry) :teleporter/fx-crit-hit)
                               (swap! crit-fx-calls* conj payload))
                             nil)
                  ach-dispatcher/trigger-custom-event! (fn [player-id event-id]
                                                         (swap! ach-calls* conj [player-id event-id]))
                  rand (fn [] 0.0)]
      (cb/apply-invoke tt/threatening-tp-up! :player-id "p1" :ctx-id "ctx-3" :player-ref :player :cost-ok? true))

    (is (= [["minecraft:overworld" "enemy" 4.0]] @damage-calls*))
    (is (= 0 @consume-calls*))
    (is (= [[1 4.0 5.0 6.0]] @drop-calls*))
    (is (= [["p1" :threatening-teleport 0.003]] @exp-calls*))
    (is (= [["p1" :threatening-teleport 22]] @cooldown-calls*))
    (is (= [["p1" "teleporter.threatening_teleport"]] @ach-calls*))
    (let [perform-payload (some (fn [[topic payload]]
                                  (when (= topic :threatening-teleport/fx-perform)
                                    payload))
                                @fx-calls*)]
      (is (some? perform-payload))
      (is (= true (get perform-payload :attacked? false))))
    (is (= [{:x 4.0
             :y 5.0
             :z 6.0
             :crit-level 1
             :crit-rate 1.6
             :message-key "ability.teleporter.critical_hit"
             :message-args ["x1.6"]
             :target-uuid "enemy"
             :skill-id :threatening-teleport}]
           @crit-fx-calls*))))

(deftest threatening-tp-up-critical-but-not-applied-skips-crit-fx-test
  (let [{:keys [get-context]} (make-context-mocks {:skill-state {:trace {:world-id "minecraft:overworld"
                                                                          :start-x 1.0 :start-y 2.0 :start-z 3.0
                                                                          :drop-x 4.0 :drop-y 5.0 :drop-z 6.0
                                                                          :attacked? true
                                                                          :target-uuid "enemy"}}})
        fx-calls* (atom [])
        crit-fx-calls* (atom [])]
    (with-redefs [ctx/get-context get-context
                  skill-effects/skill-exp (fn [_ _] 0.5)
                  skill-config/lerp-double (fn [_ field _]
                                    (case field
                                      :combat.damage 4.0
                                      :targeting.range 10.0
                                      0.0))
                  skill-config/lerp-int (fn [_ _ _] 22)
                  skill-config/tunable-double (fn [_ field]
                                      (case field
                                        :progression.exp-base 0.003
                                        :progression.exp-hit-factor 1.0
                                        :progression.exp-miss-factor 0.2
                                        0.0))
                  skill-config/probability (fn [_ field]
                                           (case field
                                             :interaction.drop-prob.hit 0.3
                                             :interaction.drop-prob.miss 1.0
                                             0.0))
                  entity/player-get-main-hand-item-count (fn [_] 1)
                  entity/player-drop-main-hand-item-at! (fn [& _] true)
                  helper/deal-magic-damage! (fn [& _]
                                              {:critical? true
                                               :crit-level 1
                                               :applied? false})
                  skill-effects/add-skill-exp! (fn [& _] nil)
                  skill-effects/set-main-cooldown! (fn [& _] nil)
                  ach-dispatcher/trigger-custom-event! (fn [& _] nil)
                  fx/send! (fn [_ctx-id entry _evt payload]
                             (swap! fx-calls* conj [(:topic entry) payload])
                             (when (= (:topic entry) :teleporter/fx-crit-hit)
                               (swap! crit-fx-calls* conj payload))
                             nil)
                  rand (fn [] 0.0)]
      (cb/apply-invoke tt/threatening-tp-up! :player-id "p1" :ctx-id "ctx-4" :player-ref :player :cost-ok? true))

    (is (empty? @crit-fx-calls*))
    (is (some (fn [[topic _]] (= topic :threatening-teleport/fx-perform)) @fx-calls*))))

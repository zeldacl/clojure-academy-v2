(ns cn.li.ac.content.ability.meltdowner.light-shield-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.service.dispatcher :as ctx]
            [cn.li.ac.ability.util.toggle :as toggle]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.content.ability.meltdowner.light-shield :as ls]
            [cn.li.mcmod.platform.potion-effects :as potion-effects]))

(defn- reduce-damage-fn []
  (var-get #'cn.li.ac.content.ability.meltdowner.light-shield/light-shield-reduce-damage))

(deftest activate-does-not-manually-send-fx-test
  (testing "activate! initializes state but does not manually emit client FX"
    (with-redefs [ctx/update-context! (fn [& _] nil)
                  ctx/ctx-send-to-client! (fn [& _]
                                            (throw (ex-info "manual fx send should not happen" {})))
                  skill-effects/player-path (fn [& _] 12.0)
                  skill-config/tunable-int (fn [& _] 1)]
      (is (nil? (ls/light-shield-activate! {:ctx-id "ctx-1" :player-id "p-1"}))))))

(deftest reduce-damage-respects-absorb-interval-window-test
  (testing "damage is unchanged when absorb interval has not elapsed"
    (let [consume-calls* (atom 0)
          reduce-damage! (reduce-damage-fn)]
      (with-redefs [ctx/get-all-contexts (fn [] {[:server :session "ctx-1"]
                                                 {:player-uuid "p-1"
                                                  :skill-state {:light-shield {:ticks 100
                                                                               :last-absorb-tick 95}
                                                               :toggle {:light-shield {:active true}}}}})
                    toggle/is-toggle-active? (fn [_ _] true)
                    skill-config/tunable-int (fn [_skill-id field-id]
                                               (case field-id
                                                 :combat.absorb-interval-ticks 18
                                                 1))
                    skill-effects/perform-resource! (fn [& _]
                                                     (swap! consume-calls* inc)
                                                     {:success? true})]
        (is (= [10.0 nil]
           (reduce-damage! "p-1" nil 10.0 :magic)))
        (is (= 0 @consume-calls*))))))

(deftest reduce-damage-absorbs-and-updates-last-absorb-tick-test
  (testing "successful absorb clamps by absorb cap and records last absorb tick"
    (let [update-calls* (atom [])
          exp-calls* (atom [])
          reduce-damage! (reduce-damage-fn)]
      (with-redefs [ctx/get-all-contexts (fn [] {[:server :session "ctx-2"]
                                                 {:player-uuid "p-2"
                                                  :skill-state {:light-shield {:ticks 100
                                                                               :last-absorb-tick 70}
                                                               :toggle {:light-shield {:active true}}}}})
                    ctx/update-context! (fn [& args]
                                          (swap! update-calls* conj args)
                                          nil)
                    toggle/is-toggle-active? (fn [_ _] true)
                    skill-effects/skill-exp (fn [& _] 0.0)
                    skill-config/tunable-int (fn [_skill-id field-id]
                                               (case field-id
                                                 :combat.absorb-interval-ticks 18
                                                 1))
                    skill-config/lerp-double (fn [_skill-id field-id _exp]
                                               (case field-id
                                                 :combat.absorb-damage 6.0
                                                 :cost.absorb.cp 5.0
                                                 :cost.absorb.overload 0.5
                                                 0.0))
                    skill-config/tunable-double (fn [_skill-id field-id]
                                                 (case field-id
                                                   :progression.exp-absorbed-scale 0.0004
                                                   0.0))
                    skill-effects/perform-resource! (fn [& _] {:success? true})
                    skill-effects/add-skill-exp! (fn [player-id skill-id amount]
                                                   (swap! exp-calls* conj [player-id skill-id amount])
                                                   nil)]
        (is (= [4.0 {:absorbed 6.0}]
           (reduce-damage! "p-2" nil 10.0 :magic)))
        (is (= 1 (count @update-calls*)))
        (is (= [:server :session "ctx-2"] (first (first @update-calls*))))
        (is (= 1 (count @exp-calls*)))))))

(deftest tick-timeout-deactivates-toggle-test
  (testing "tick exceeding max-active-ticks removes toggle and applies deactivation effects"
    (let [remove-calls* (atom 0)
          cooldown-calls* (atom [])
          potion-calls* (atom 0)]
      (with-redefs [ctx/get-context (fn [_]
                                      {:player-uuid "p-3"
                                       :skill-state {:light-shield {:ticks 5
                                                                    :last-touch-tick 0
                                                                    :overload-floor 10.0}
                                                    :toggle {:light-shield {:active true}}}})
                    ctx/update-context! (fn [& _] nil)
                    toggle/is-toggle-active? (fn [_ _] true)
                    toggle/remove-toggle! (fn [& _] (swap! remove-calls* inc))
                    skill-effects/skill-exp (fn [& _] 0.0)
                    skill-effects/player-path (fn [& _] nil)
                    skill-config/lerp-int (fn [_skill-id field-id _exp]
                                            (case field-id
                                              :timing.max-active-ticks 5
                                              :cooldown.ticks 100
                                              1))
                    skill-config/tunable-int (fn [_skill-id field-id]
                                               (case field-id
                                                 :effect.deactivate-slowness-duration-ticks 60
                                                 :effect.slowness-amplifier 1
                                                 :combat.touch-interval-ticks 4
                                                 1))
                    skill-effects/enforce-overload-floor! (fn [& _] true)
                    skill-effects/set-main-cooldown! (fn [player-id skill-id ticks]
                                                       (swap! cooldown-calls* conj [player-id skill-id ticks])
                                                       true)
                    potion-effects/*potion-effects* :mock
                    potion-effects/apply-potion-effect! (fn [& _]
                                                          (swap! potion-calls* inc)
                                                          nil)]
        (ls/light-shield-tick! {:player-id "p-3" :ctx-id "ctx-3" :cost-ok? true})
        (is (= 1 @remove-calls*))
        (is (= 1 @potion-calls*))
        (is (= 1 (count @cooldown-calls*)))))))

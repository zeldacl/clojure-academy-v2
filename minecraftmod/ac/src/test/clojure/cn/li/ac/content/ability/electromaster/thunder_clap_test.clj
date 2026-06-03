(ns cn.li.ac.content.ability.electromaster.thunder-clap-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.context-skill-state :as ctx-skill]
            [cn.li.ac.ability.effects.damage :as damage-op]
            [cn.li.ac.ability.effects.geom :as geom]
            [cn.li.ac.ability.effects.world :as world-op]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.content.ability.electromaster.thunder-clap :as thunder-clap]))

(def ^:private spec thunder-clap/thunder-clap)

(defn- context-mocks
  [initial]
  (let [ctx* (atom initial)
        terminate-calls* (atom [])]
    {:ctx* ctx*
     :terminate-calls* terminate-calls*
     :get-context (fn [_] @ctx*)
     :update-skill-state-root! (fn [_ctx-id f & args]
                                 (swap! ctx* update :skill-state #(apply f (or % {}) args)))
     :terminate-context! (fn [ctx-id terminate-fn]
                           (swap! terminate-calls* conj [ctx-id terminate-fn])
                           nil)}))

(deftest thunder-clap-public-spec-uses-action-tunables-test
  (testing "ThunderClap cost and targeting values exposed through the public skill spec"
    (let [spec thunder-clap/thunder-clap
          ctx* (atom {:skill-state {}})
          down-overload (get-in spec [:cost :down :overload])
          tick-cp (get-in spec [:cost :tick :cp])
          down-action (get-in spec [:actions :down!])
          tick-action (get-in spec [:actions :tick!])]
      (with-redefs [thunder-clap/cfg-lerp (fn [field exp]
                                            (case field
                                              :cost.down.overload (+ 100.0 (* 200.0 exp))
                                              :cost.tick.cp (+ 10.0 (* 20.0 exp))
                                              0.0))
                    thunder-clap/min-ticks (fn [] 50)
                    thunder-clap/targeting-range (fn [] 77.0)
                    thunder-clap/resolve-raycast-target (fn [_] {:x 1.0 :y 2.0 :z 3.0})
                    ctx/get-context (fn ([_] @ctx*) ([_ _] @ctx*))
                    ctx-skill/update-skill-state-root! (fn [_ f & args]
                                                         (swap! ctx* update :skill-state #(apply f (or % {}) args))
                                                         nil)]
        (is (= 200.0 (down-overload {:exp 0.5})))
        (is (= 20.0 (tick-cp {:hold-ticks 50 :exp 0.5})))
        (is (= 0.0 (tick-cp {:hold-ticks 51 :exp 0.5})))
        (is (nil? (down-action {:ctx-id "ctx-1" :player-id "p1"})))
        (is (nil? (tick-action {:ctx-id "ctx-1" :player-id "p1"})))))))

(deftest thunder-clap-short-release-remains-unperformed-test
  (testing "release before the minimum charge keeps performed false and surfaces it through the end payload"
    (let [up-fn (get-in spec [:actions :up!])
          end-payload-fn (get-in spec [:fx :end :payload])
          settle-perform? (get-in spec [:input-policy :settle-perform-on-key-up?])
          {:keys [ctx* get-context update-skill-state-root!]} (context-mocks {:skill-state {:hold-ticks 20
                                                                                   :performed? false
                                                                                   :hit-pos {:x 1.0 :y 2.0 :z 3.0}}})
          cooldown-calls* (atom [])
          exp-calls* (atom [])
          run-ops* (atom [])]
      (with-redefs [thunder-clap/min-ticks (fn [] 40)
                    thunder-clap/max-ticks (fn [] 60)
                    ctx/get-context get-context
                    ctx-skill/update-skill-state-root! update-skill-state-root!
                    skill-effects/set-main-cooldown! (fn [& args]
                                                       (swap! cooldown-calls* conj args))
                    skill-effects/add-skill-exp! (fn [& args]
                                                   (swap! exp-calls* conj args))
                    world-op/execute-spawn-lightning! (fn [_evt _params]
                                                        (swap! run-ops* conj [:spawn-lightning]))
                    damage-op/execute-damage-aoe! (fn [_evt params]
                                                    (swap! run-ops* conj [:damage-aoe params]))]
        (up-fn {:player-id "p1" :ctx-id "ctx-short" :hold-ticks 20 :exp 0.5})
        (is (false? (get-in @ctx* [:skill-state :performed?])))
        (is (= {:x 1.0 :y 2.0 :z 3.0}
               (get-in @ctx* [:skill-state :final-target])))
        (is (false? (settle-perform? {:ctx-id "ctx-short"})))
        (is (= {:performed? false
                :charge-ticks 20
                :ticks 20
                :charge-ratio 0.0
                :target {:x 1.0 :y 2.0 :z 3.0}}
               (end-payload-fn {:ctx-id "ctx-short" :player-id "p1" :hold-ticks 20})))
        (is (empty? @cooldown-calls*))
        (is (empty? @exp-calls*))
        (is (empty? @run-ops*))))))

(deftest thunder-clap-successful-release-marks-performed-and-applies-effects-test
  (testing "release after the charge threshold records a performed strike and executes AOE ops"
    (let [up-fn (get-in spec [:actions :up!])
          end-payload-fn (get-in spec [:fx :end :payload])
          settle-perform? (get-in spec [:input-policy :settle-perform-on-key-up?])
          {:keys [ctx* get-context update-skill-state-root!]} (context-mocks {:skill-state {:hold-ticks 50
                                                                                   :performed? false
                                                                                   :hit-pos {:x 8.0 :y 64.0 :z 8.0}}})
          cooldown-calls* (atom [])
          exp-calls* (atom [])
          run-ops* (atom [])]
      (with-redefs [thunder-clap/min-ticks (fn [] 40)
                    thunder-clap/max-ticks (fn [] 60)
                    thunder-clap/cfg-lerp (fn [field exp]
                                            (case field
                                              :combat.overcharge-multiplier (+ 1.0 (* 0.2 exp))
                                              :combat.damage (+ 36.0 (* 36.0 exp))
                                              :combat.aoe-radius (+ 15.0 (* 15.0 exp))
                                              :cooldown.ticks-per-hold (+ 10.0 (* -4.0 exp))
                                              0.0))
                    thunder-clap/cfg-double (fn [field]
                                              (case field
                                                :progression.exp-use 0.003
                                                0.0))
                    ctx/get-context get-context
                    ctx-skill/update-skill-state-root! update-skill-state-root!
                    geom/world-id-of (fn [_] "world-1")
                    skill-effects/set-main-cooldown! (fn [& args]
                                                       (swap! cooldown-calls* conj args))
                    skill-effects/add-skill-exp! (fn [& args]
                                                   (swap! exp-calls* conj args))
                    world-op/execute-spawn-lightning! (fn [evt params]
                                                        (swap! run-ops* conj [:spawn-lightning evt params]))
                    damage-op/execute-damage-aoe! (fn [evt params]
                                                    (swap! run-ops* conj [:damage-aoe evt params]))]
        (up-fn {:player-id "p1" :ctx-id "ctx-hit" :hold-ticks 50 :exp 0.5})
        (is (true? (get-in @ctx* [:skill-state :performed?])))
        (is (= {:x 8.0 :y 64.0 :z 8.0}
               (get-in @ctx* [:skill-state :final-target])))
        (is (true? (settle-perform? {:ctx-id "ctx-hit"})))
        (is (= [[:spawn-lightning
           {:player-id "p1"
            :ctx-id "ctx-hit"
            :world-id "world-1"
            :hit-pos {:x 8.0 :y 64.0 :z 8.0}
            :exp 0.5}
           {:at :hit-pos}]
          [:damage-aoe
           {:player-id "p1"
            :ctx-id "ctx-hit"
            :world-id "world-1"
            :hit-pos {:x 8.0 :y 64.0 :z 8.0}
            :exp 0.5}
           {:center :hit-pos
            :radius 22.5
            :amount 59.400000000000006
            :damage-type :lightning}]]
               @run-ops*))
        (is (= [["p1" :thunder-clap 400]] @cooldown-calls*))
        (is (= [["p1" :thunder-clap 0.003]] @exp-calls*))
        (is (= {:performed? true
                :charge-ticks 50
                :ticks 50
                :charge-ratio 0.5
                :target {:x 8.0 :y 64.0 :z 8.0}}
               (end-payload-fn {:ctx-id "ctx-hit" :player-id "p1" :hold-ticks 50})))))))

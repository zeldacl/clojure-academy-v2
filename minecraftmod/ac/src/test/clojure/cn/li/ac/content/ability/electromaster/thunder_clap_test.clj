(ns cn.li.ac.content.ability.electromaster.thunder-clap-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cn.li.ac.ability.test.skill-callback-test-helpers :as cb]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.context-skill-state :as ctx-skill]
            [cn.li.ac.ability.effects.damage :as damage-op]
            [cn.li.ac.ability.effects.geom :as geom]
            [cn.li.ac.ability.effects.world :as world-op]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.content.ability.electromaster.thunder-clap :as thunder-clap]))

(def ^:private spec thunder-clap/thunder-clap)

(defn- reset-state! [f]
  (let [context-registry-val (ctx/snapshot-context-registry)]
    (try
      (ctx/reset-contexts-for-test!)
      (f)
      (finally
        (ctx/reset-contexts-for-test! context-registry-val)))))

(use-fixtures :each reset-state!)

(defn- seed-charge-context!
  "thunder-clap-up! ignores the (never-populated-in-production) hold-ticks
  positional argument and instead self-tracks charge duration in
  :skill-state — so tests must seed a real registered context rather than
  passing :hold-ticks through cb/apply-invoke."
  [owner player-id ctx-id skill-state]
  (ctx/with-context-owner owner
    (ctx/register-context!
     (assoc (ctx/new-server-context player-id :thunder-clap ctx-id owner)
            :status ctx/STATUS-ALIVE))
    (ctx-skill/update-skill-state-root! ctx-id identity skill-state)))

(deftest thunder-clap-short-release-remains-unperformed-test
  (testing "release before the minimum charge keeps performed false and surfaces it through the end payload"
    (let [up-fn (get-in spec [:actions :up!])
          end-payload-fn (get-in spec [:fx :end :payload])
          settle-perform? (get-in spec [:input-policy :settle-perform-on-key-up?])
          owner {:logical-side :server :server-session-id :test-session :player-uuid "p1"}
          cooldown-calls* (atom [])
          exp-calls* (atom [])
          run-ops* (atom [])]
      (with-redefs [thunder-clap/min-ticks (fn [] 40)
                    thunder-clap/max-ticks (fn [] 60)
                    skill-effects/set-main-cooldown! (fn [& args]
                                                       (swap! cooldown-calls* conj args))
                    skill-effects/add-skill-exp! (fn [& args]
                                                   (swap! exp-calls* conj args))
                    world-op/execute-spawn-lightning! (fn [_evt _params]
                                                        (swap! run-ops* conj [:spawn-lightning]))
                    damage-op/execute-damage-aoe! (fn [_evt params]
                                                    (swap! run-ops* conj [:damage-aoe params]))]
        (seed-charge-context! owner "p1" "ctx-short" {:hold-ticks 20
                                                       :performed? false
                                                       :hit-pos {:x 1.0 :y 2.0 :z 3.0}})
        (ctx/with-context-owner owner
          (cb/apply-invoke up-fn :player-id "p1" :ctx-id "ctx-short" :exp 0.5)
          (is (false? (get-in (ctx/get-context "ctx-short") [:skill-state :performed?])))
          (is (= {:x 1.0 :y 2.0 :z 3.0}
                 (get-in (ctx/get-context "ctx-short") [:skill-state :final-target])))
          (is (false? (settle-perform? {:ctx-id "ctx-short"})))
          (is (= {:performed? false
                  :charge-ticks 20
                  :ticks 20
                  :charge-ratio 0.0
                  :target {:x 1.0 :y 2.0 :z 3.0}}
                 (end-payload-fn {:ctx-id "ctx-short" :player-id "p1" :hold-ticks 20}))))
        (is (empty? @cooldown-calls*))
        (is (empty? @exp-calls*))
        (is (empty? @run-ops*))))))

(deftest thunder-clap-successful-release-marks-performed-and-applies-effects-test
  (testing "release after the charge threshold records a performed strike and executes AOE ops"
    (let [up-fn (get-in spec [:actions :up!])
          end-payload-fn (get-in spec [:fx :end :payload])
          settle-perform? (get-in spec [:input-policy :settle-perform-on-key-up?])
          owner {:logical-side :server :server-session-id :test-session :player-uuid "p1"}
          cooldown-calls* (atom [])
          exp-calls* (atom [])
          run-ops* (atom [])]
      (with-redefs [thunder-clap/min-ticks (fn [] 40)
                    thunder-clap/max-ticks (fn [] 60)
                    thunder-clap/cfg-lerp (fn [field exp]
                                            (case field
                                              ;; combat.overcharge-multiplier is looked up by
                                              ;; charge-ratio, not skill exp — see
                                              ;; compute-overcharge-ratio in thunder_clap.clj.
                                              :combat.overcharge-multiplier (+ 1.0 (* 0.2 exp))
                                              :combat.damage (+ 36.0 (* 36.0 exp))
                                              :combat.aoe-radius (+ 15.0 (* 15.0 exp))
                                              :cooldown.ticks-per-hold (+ 10.0 (* -4.0 exp))
                                              0.0))
                    thunder-clap/cfg-double (fn [field]
                                              (case field
                                                :progression.exp-use 0.003
                                                0.0))
                    geom/world-id-of (fn [_] "world-1")
                    skill-effects/set-main-cooldown! (fn [& args]
                                                       (swap! cooldown-calls* conj args))
                    skill-effects/add-skill-exp! (fn [& args]
                                                   (swap! exp-calls* conj args))
                    world-op/execute-spawn-lightning! (fn [evt params]
                                                        (swap! run-ops* conj [:spawn-lightning evt params]))
                    damage-op/execute-damage-aoe! (fn [evt params]
                                                    (swap! run-ops* conj [:damage-aoe evt params]))]
        (seed-charge-context! owner "p1" "ctx-hit" {:hold-ticks 50
                                                     :performed? false
                                                     :hit-pos {:x 8.0 :y 64.0 :z 8.0}})
        (ctx/with-context-owner owner
          (cb/apply-invoke up-fn :player-id "p1" :ctx-id "ctx-hit" :exp 0.5)
          (is (true? (get-in (ctx/get-context "ctx-hit") [:skill-state :performed?])))
          (is (= {:x 8.0 :y 64.0 :z 8.0}
                 (get-in (ctx/get-context "ctx-hit") [:skill-state :final-target])))
          (is (true? (settle-perform? {:ctx-id "ctx-hit"})))
          ;; ticks=50, min=40, max=60 => charge-ratio=(50-40)/60=1/6 =>
          ;; overcharge-multiplier=1.0+0.2*(1/6)=31/30 => dmg=54*31/30=55.8
          (is (= [[:spawn-lightning
             {:player-id "p1"
              :ctx-id "ctx-hit"
              :world-id "world-1"
              :hit-pos {:x 8.0 :y 64.0 :z 8.0}
              :exp 0.5}
             {:at :hit-pos :visual-only? true}]
            [:damage-aoe
             {:player-id "p1"
              :ctx-id "ctx-hit"
              :world-id "world-1"
              :hit-pos {:x 8.0 :y 64.0 :z 8.0}
              :exp 0.5}
             {:center :hit-pos
              :radius 22.5
              :amount 55.800000000000004
              :damage-type :lightning}]]
                 @run-ops*))
          (is (= [["p1" :thunder-clap 400]] @cooldown-calls*))
          (is (= [["p1" :thunder-clap 0.003]] @exp-calls*))
          (is (= {:performed? true
                  :charge-ticks 50
                  :ticks 50
                  :charge-ratio (/ 1.0 6.0)
                  :target {:x 8.0 :y 64.0 :z 8.0}}
                 (end-payload-fn {:ctx-id "ctx-hit" :player-id "p1" :hold-ticks 50}))))))))

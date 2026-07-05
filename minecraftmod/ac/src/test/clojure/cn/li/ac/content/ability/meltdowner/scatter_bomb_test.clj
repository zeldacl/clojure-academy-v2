(ns cn.li.ac.content.ability.meltdowner.scatter-bomb-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.test.skill-callback-test-helpers :as cb]
            [cn.li.ac.ability.fx :as fx]
            [cn.li.ac.test.support.fx-mocks :as fx-mocks]
            [cn.li.ac.test.support.skill-context :as skill-ctx]
            [cn.li.ac.content.ability.meltdowner.scatter-bomb :as scatter]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.context-skill-state :as ctx-skill]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.service.delayed-projectiles :as delayed-projectiles]
            [cn.li.ac.ability.effects.geom :as geom]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.raycast :as raycast]))

(defn- context-mocks
  [initial]
  (let [ctx* (atom initial)
        {:keys [calls* send!]} (fx-mocks/capture-fx-send!)]
    {:ctx* ctx*
     :messages* calls*
     :get-context (fn
                    ([_ctx-id] @ctx*)
                    ([_owner _ctx-id] @ctx*))
     :update-skill-state-root! (fn [_ctx-id f & args]
                                 (swap! ctx*
                                        (fn [ctx-data]
                                          (let [current (or (:skill-state ctx-data) {})
                                                next-state (if (and (= f identity) (= 1 (count args)))
                                                             (first args)
                                                             (apply f current args))]
                                            (assoc ctx-data :skill-state next-state))))
                                 nil)
     :assoc-skill-state! (fn [_ctx-id k v]
                            (swap! ctx*
                                   (fn [ctx-data]
                                     (let [path (if (vector? k) k [k])]
                                       (update ctx-data :skill-state #(assoc-in (or % {}) path v)))))
                            nil)
     :send! send!}))

(defn- with-scatter-env [f]
  (skill-ctx/with-server-skill-context f))

(defn- stub-lerp-double [_skill-id field-id _exp]
  (case field-id
    :cost.down.overload 150.0
    :cost.tick.cp 8.0
    :combat.damage 7.0
    :cooldown.ticks-per-ball 30.0
    0.0))

(defn- stub-tunable-double [_skill-id field-id]
  (case field-id
    :cost.overload-floor-scale 0.8
    :effect.anti-afk-damage 6.0
    :beam.radius 0.3
    :beam.query-radius 20.0
    :beam.step 0.8
    :beam.max-distance 25.0
    :beam.visual-distance 23.0
    :progression.exp-per-ball 0.002
    0.0))

(defn- stub-tunable-int [_skill-id field-id]
  (case field-id
    :effect.anti-afk-tick 200
    :projectile.max-hold-ticks 80
    :projectile.spawn-start-tick 20
    :projectile.max-balls 6
    :projectile.spawn-interval-ticks 10
    0))

(deftest scatter-bomb-down-initializes-state-and-start-fx-test
  (let [{:keys [ctx* update-skill-state-root! get-context assoc-skill-state! send! messages*]}
        (context-mocks {:skill-state {:legacy true}})]
    (with-scatter-env
      #(with-redefs [skill-effects/skill-exp (fn [& _] 0.0)
                      skill-config/lerp-double stub-lerp-double
                      skill-config/tunable-double stub-tunable-double
                      ctx/get-context get-context
                      ctx-skill/update-skill-state-root! update-skill-state-root!
                      ctx-skill/assoc-skill-state! assoc-skill-state!
                      fx/send! send!]
         (cb/apply-invoke scatter/scatter-bomb-down! :player-id "p1" :ctx-id "ctx-1" :cost-ok? true)))
    (is (= {:balls 0 :hold-ticks 0 :overload-floor 120.0}
           (:skill-state @ctx*)))
    (is (= [["ctx-1" :scatter-bomb/fx-start nil {}]] @messages*))))

(deftest scatter-bomb-tick-spawns-ball-at-cadence-test
  (let [{:keys [ctx* get-context update-skill-state-root! assoc-skill-state! send! messages*]}
        (context-mocks {:skill-state {:balls 0 :hold-ticks 19 :overload-floor 120.0}})
        floor-calls* (atom [])
        spawn-calls* (atom [])
        damage-calls* (atom [])]
    (with-scatter-env
      #(with-redefs [ctx/get-context get-context
                    ctx-skill/update-skill-state-root! update-skill-state-root!
                    ctx-skill/assoc-skill-state! assoc-skill-state!
                    fx/send! send!
                  skill-config/tunable-int stub-tunable-int
                  skill-config/tunable-double stub-tunable-double
                  skill-effects/enforce-overload-floor! (fn [player-id floor]
                                                          (swap! floor-calls* conj [player-id floor])
                                                          nil)
                  geom/world-id-of (fn [_] "w")
                  entity/player-spawn-entity-by-id! (fn [& args]
                                                      (swap! spawn-calls* conj args)
                                                      true)
                  geom/eye-pos (fn [_] {:x 1.0 :y 64.0 :z 2.0})
                  entity-damage/available? (constantly true)
                  entity-damage/apply-direct-damage!* (fn [world-id entity-uuid damage source-type]
                                                       (swap! damage-calls* conj [world-id entity-uuid damage source-type])
                                                       true)]
         (cb/apply-invoke scatter/scatter-bomb-tick! :player-id "p1" :ctx-id "ctx-2" :player-ref {:id "player-obj"})))
    (is (= [["p1" 120.0]] @floor-calls*))
    (is (empty? @damage-calls*))
    (is (= [[{:id "player-obj"} "my_mod:entity_md_ball" 0.0]] @spawn-calls*))
    (is (= 20 (get-in @ctx* [:skill-state :hold-ticks])))
    (is (= 1 (get-in @ctx* [:skill-state :balls])))
    (is (= :scatter-bomb/fx-ball (second (first @messages*))))))

(deftest scatter-bomb-tick-anti-afk-damages-and-terminates-test
  (let [{:keys [ctx* get-context update-skill-state-root! assoc-skill-state! send! messages*]}
        (context-mocks {:skill-state {:balls 2 :hold-ticks 199 :overload-floor 120.0}})
        damage-calls* (atom [])
        terminate-calls* (atom [])]
    (with-scatter-env
      #(with-redefs [ctx/get-context get-context
                    ctx-skill/update-skill-state-root! update-skill-state-root!
                    ctx-skill/assoc-skill-state! assoc-skill-state!
                    fx/send! send!
                  ctx/terminate-context! (fn [ctx-id terminate-fn]
                                           (swap! terminate-calls* conj [ctx-id terminate-fn])
                                           nil)
                  skill-config/tunable-int stub-tunable-int
                  skill-config/tunable-double stub-tunable-double
                  skill-effects/enforce-overload-floor! (fn [& _] nil)
                  geom/world-id-of (fn [_] "w")
                  geom/eye-pos (fn [_] {:x 1.0 :y 64.0 :z 2.0})
                  entity/player-spawn-entity-by-id! (fn [& _] true)
                  entity-damage/available? (constantly true)
                  entity-damage/apply-direct-damage!* (fn [world-id entity-uuid damage source-type]
                                                       (swap! damage-calls* conj [world-id entity-uuid damage source-type])
                                                       true)]
         (cb/apply-invoke scatter/scatter-bomb-tick! :player-id "p1" :ctx-id "ctx-afk" :player-ref {:id "player-obj"})))
    (is (= [["w" "p1" 6.0 :magic]] @damage-calls*))
    (is (= [["ctx-afk" nil]] @terminate-calls*))
    (is (= ["ctx-afk" :scatter-bomb/fx-end nil {:balls 2}] (last @messages*)))))

(deftest scatter-bomb-tick-after-hold-window-does-not-spawn-new-ball-test
  (let [{:keys [ctx* get-context update-skill-state-root! assoc-skill-state! send! messages*]}
        (context-mocks {:skill-state {:balls 4 :hold-ticks 80 :overload-floor 120.0}})
        spawn-calls* (atom [])]
    (with-scatter-env
      #(with-redefs [ctx/get-context get-context
                    ctx-skill/update-skill-state-root! update-skill-state-root!
                    ctx-skill/assoc-skill-state! assoc-skill-state!
                    fx/send! send!
                  skill-config/tunable-int stub-tunable-int
                  skill-config/tunable-double stub-tunable-double
                  skill-effects/enforce-overload-floor! (fn [& _] nil)
                  geom/world-id-of (fn [_] "w")
                  entity/player-spawn-entity-by-id! (fn [& args]
                                                      (swap! spawn-calls* conj args)
                                                      true)
                  geom/eye-pos (fn [_] {:x 1.0 :y 64.0 :z 2.0})
                  entity-damage/available? (constantly true)
                  entity-damage/apply-direct-damage!* (fn [_ _ _ _ _] true)]
         (cb/apply-invoke scatter/scatter-bomb-tick! :player-id "p1" :ctx-id "ctx-window" :player-ref {:id "player-obj"})))
    (is (= 81 (get-in @ctx* [:skill-state :hold-ticks])))
    (is (= 4 (get-in @ctx* [:skill-state :balls])))
    (is (empty? @spawn-calls*))
    (is (empty? @messages*))))

(deftest scatter-bomb-up-schedules-delayed-beams-and-settles-rewards-test
  (let [{:keys [get-context send! messages*]}
        (context-mocks {:skill-state {:balls 3 :hold-ticks 50 :overload-floor 120.0}})
        scheduled* (atom [])
        exp-calls* (atom [])
        cooldown-calls* (atom [])]
    (with-scatter-env
      #(with-redefs [ctx/get-context get-context
                  fx/send! send!
                  skill-effects/skill-exp (fn [& _] 0.0)
                  skill-config/lerp-double stub-lerp-double
                  skill-config/tunable-double stub-tunable-double
                  skill-config/tunable-int stub-tunable-int
                  skill-config/tunable-double-list (fn [& _] [0.3 0.3])
                  skill-effects/add-skill-exp! (fn [& args]
                                                 (swap! exp-calls* conj args)
                                                 nil)
                  skill-effects/set-main-cooldown! (fn [& args]
                                                     (swap! cooldown-calls* conj args)
                                                     nil)
                  delayed-projectiles/mdball-near-expire-delay (fn [] 15)
                  delayed-projectiles/schedule-scatter-bomb-beam! (fn [task]
                                                                    (swap! scheduled* conj task)
                                                                    nil)
                  scatter/*scatter-direction-sampler* (fn [_]
                                                        {:x 1.0 :y 0.0 :z 0.0})
                  geom/world-id-of (fn [_] "w")
                  geom/eye-pos (fn [_] {:x 1.0 :y 64.0 :z 2.0})
                  raycast/available? (constantly true)
                  raycast/get-player-look-vector* (fn [_] {:x 0.0 :y 0.0 :z 1.0})]
         (cb/apply-invoke scatter/scatter-bomb-up! :player-id "p1" :ctx-id "ctx-3")))

    (is (= [15 16 17] (mapv :delay-ticks @scheduled*)))
    (is (= [{:x 1.0 :y 0.0 :z 0.0}
            {:x 1.0 :y 0.0 :z 0.0}
            {:x 1.0 :y 0.0 :z 0.0}]
           (mapv :look-dir @scheduled*)))
    (is (= [["p1" :scatter-bomb 0.006]] @exp-calls*))
    (is (= [["p1" :scatter-bomb 90]] @cooldown-calls*))
    (is (= ["ctx-3" :scatter-bomb/fx-end nil {:balls 3}] (last @messages*)))))

(deftest scatter-bomb-cost-fail-sends-fx-end-test
  (let [{:keys [get-context send! messages*]}
        (context-mocks {:skill-state {:balls 4 :hold-ticks 21 :overload-floor 120.0}})]
    (with-scatter-env
      #(with-redefs [ctx/get-context get-context
                  fx/send! send!]
         (cb/apply-invoke scatter/scatter-bomb-cost-fail! :ctx-id "ctx-fail" :cost-stage :tick)))
    (is (= [["ctx-fail" :scatter-bomb/fx-end nil {:balls 4}]] @messages*))))


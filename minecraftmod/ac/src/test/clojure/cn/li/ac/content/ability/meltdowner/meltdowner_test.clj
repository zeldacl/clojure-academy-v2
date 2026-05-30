(ns cn.li.ac.content.ability.meltdowner.meltdowner-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.content.ability.meltdowner.meltdowner :as meltdowner]
            [cn.li.ac.content.ability.meltdowner.damage-helper :as damage-helper]
            [cn.li.ac.ability.service.dispatcher :as ctx]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.server.effect.geom :as geom]
            [cn.li.ac.ability.server.effect.core :as effect]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.raycast :as raycast]))

(defn- context-mocks []
  (let [messages* (atom [])
        terminated* (atom [])]
    {:messages* messages*
     :terminated* terminated*
     :send! (fn [ctx-id channel payload]
              (swap! messages* conj {:ctx-id ctx-id :channel channel :payload payload})
              nil)
     :terminate! (fn [ctx-id terminate-fn]
                   (swap! terminated* conj [ctx-id terminate-fn])
                   nil)}))

(defn- raycast-stub
  [look-vec raycast-entity-fn]
  (reify raycast/IRaycast
    (raycast-blocks [_ _ _ _ _ _ _ _ _] nil)
    (raycast-entities [_ world-id sx sy sz dx dy dz max-distance]
      (raycast-entity-fn world-id sx sy sz dx dy dz max-distance))
    (raycast-combined [_ _ _ _ _ _ _ _ _] nil)
    (get-player-look-vector [_ _] look-vec)
    (raycast-from-player [_ _player-uuid _max-dist _living?] nil)))

(deftest meltdowner-up-insufficient-charge-ends-context-test
  (let [{:keys [messages* terminated* send! terminate!]} (context-mocks)
        exp-calls* (atom [])
        cooldown-calls* (atom [])]
    (with-redefs [ctx/ctx-send-to-client! send!
                  ctx/terminate-context! terminate!
                  skill-effects/skill-exp (fn [_ _] 0.3)
                  skill-effects/add-skill-exp! (fn [& args] (swap! exp-calls* conj args))
                  skill-effects/set-main-cooldown! (fn [& args] (swap! cooldown-calls* conj args))
                  skill-config/tunable-int (fn [_ field-id]
                                             (case field-id
                                               :charge.min-ticks 20
                                               :charge.max-ticks 40
                                               :charge.max-tolerant-ticks 100
                                               0))]
      (#'cn.li.ac.content.ability.meltdowner.meltdowner/meltdowner-on-up!
       {:player-id "p1" :ctx-id "ctx-1" :hold-ticks 10})
      (is (= [{:ctx-id "ctx-1" :channel :meltdowner/fx-end :payload {:performed? false}}]
             @messages*))
      (is (= [["ctx-1" nil]] @terminated*))
      (is (empty? @exp-calls*))
      (is (empty? @cooldown-calls*)))))

(deftest meltdowner-up-success-applies-rewards-and-terminates-test
  (let [{:keys [messages* terminated* send! terminate!]} (context-mocks)
        exp-calls* (atom [])
        cooldown-calls* (atom [])]
    (with-redefs [ctx/ctx-send-to-client! send!
                  ctx/terminate-context! terminate!
                  skill-effects/skill-exp (fn [_ _] 0.6)
                  skill-effects/add-skill-exp! (fn [& args] (swap! exp-calls* conj args))
                  skill-effects/set-main-cooldown! (fn [& args] (swap! cooldown-calls* conj args))
                  skill-config/tunable-int (fn [_ field-id]
                                             (case field-id
                                               :charge.min-ticks 20
                                               :charge.max-ticks 40
                                               :charge.max-tolerant-ticks 100
                                               0))
                  skill-config/tunable-double (fn [_ field-id]
                                                (case field-id
                                                  :progression.exp-use 0.002
                                                  :cooldown.base-multiplier 20.0
                                                  :beam.query-radius 30.0
                                                  :beam.step 0.9
                                                  :beam.max-distance 50.0
                                                  :beam.visual-distance 45.0
                                                  0.0))
                  skill-config/lerp-double (fn [_ field-id _]
                                             (case field-id
                                               :charge.time-rate 1.1
                                               :cooldown.ticks 12.0
                                               :combat.damage 30.0
                                               :beam.radius 2.5
                                               :beam.block-energy 500.0
                                               0.0))
                  effect/run-op! (fn [_ _] {:beam-result {:performed? true :reflection-hit? false}})
                  geom/world-id-of (fn [_] "w")
                  geom/eye-pos (fn [_] {:x 0.0 :y 64.0 :z 0.0})]
      (binding [raycast/*raycast* (raycast-stub {:x 0.0 :y 0.0 :z 1.0}
                     (fn [& _] nil))]
      (#'cn.li.ac.content.ability.meltdowner.meltdowner/meltdowner-on-up!
       {:player-id "p1" :ctx-id "ctx-2" :hold-ticks 30}))
      (is (= [["p1" :meltdowner 0.0022]] @exp-calls*))
      (is (= [["p1" :meltdowner 264]] @cooldown-calls*))
      (is (= [{:ctx-id "ctx-2" :channel :meltdowner/fx-end :payload {:performed? true}}]
             @messages*))
      (is (= [["ctx-2" nil]] @terminated*)))))

(deftest meltdowner-tick-over-tolerant-aborts-test
  (let [{:keys [messages* terminated* send! terminate!]} (context-mocks)
        overload-calls* (atom [])]
    (with-redefs [ctx/ctx-send-to-client! send!
                  ctx/terminate-context! terminate!
                  ctx/get-context (fn [_] {:skill-state {:overload-floor 123.0}})
                  skill-effects/enforce-overload-floor!
                  (fn [player-id floor] (swap! overload-calls* conj [player-id floor]))
                  skill-config/tunable-int (fn [_ field-id]
                                             (case field-id
                                               :charge.min-ticks 20
                                               :charge.max-ticks 40
                                               :charge.max-tolerant-ticks 100
                                               0))]
      (#'cn.li.ac.content.ability.meltdowner.meltdowner/meltdowner-on-tick!
       {:player-id "p1" :ctx-id "ctx-3" :hold-ticks 101})
      (is (= [["p1" 123.0]] @overload-calls*))
      (is (= [{:ctx-id "ctx-3" :channel :meltdowner/fx-end :payload {:performed? false}}]
             @messages*))
      (is (= [["ctx-3" nil]] @terminated*)))))

(deftest reflection-shot-supports-delta-look-vector-test
  (let [fx-calls* (atom [])
        mark-calls* (atom [])
        ray-input* (atom nil)
        damage-calls* (atom [])]
    (with-redefs [geom/eye-pos (fn [_] {:x 1.0 :y 64.0 :z 2.0})
                  geom/world-id-of (fn [_] "w")
                  geom/vnorm (fn [v] v)
                  geom/v* (fn [v dist] {:x (* (:x v) dist) :y (* (:y v) dist) :z (* (:z v) dist)})
                  geom/v+ (fn [a b] {:x (+ (:x a) (:x b)) :y (+ (:y a) (:y b)) :z (+ (:z a) (:z b))})
                  ctx/ctx-send-to-client! (fn [ctx-id channel payload]
                                            (swap! fx-calls* conj [ctx-id channel payload])
                                            nil)
                  damage-helper/mark-target! (fn [source-id target-id]
                                               (swap! mark-calls* conj [source-id target-id])
                                               nil)
                  skill-config/tunable-double (fn [_ field-id]
                                                (case field-id
                                                  :reflection.shot-distance 10.0
                                                  :reflection.damage-multiplier 0.5
                                                  0.0))
                  skill-config/lerp-double (fn [_ field-id _]
                                             (case field-id
                                               :combat.damage 40.0
                                               0.0))]
      (binding [raycast/*raycast* (raycast-stub {:dx 0.0 :dy 0.0 :dz 1.0}
                                                 (fn [world-id sx sy sz dx dy dz max-distance]
                                                   (reset! ray-input* {:world-id world-id
                                                                       :start [sx sy sz]
                                                                       :dir [dx dy dz]
                                                                       :max-distance max-distance})
                                                   {:hit-type :entity :uuid "target-1"}))
                entity-damage/*entity-damage* (reify entity-damage/IEntityDamage
                                               (apply-direct-damage! [_ world-id entity-id damage source-type]
                                                 (swap! damage-calls* conj [world-id entity-id damage source-type])
                                                 true)
                                               (apply-aoe-damage! [_ _ _ _ _ _ _ _ _] [])
                                               (apply-reflection-damage! [_ _ _ _ _ _ _] []))]
        (is (true?
             (#'cn.li.ac.content.ability.meltdowner.meltdowner/perform-reflection-shot!
              "ctx-r" "reflector-p" 0.0))))
      (is (= [0.0 0.0 1.0] (:dir @ray-input*)))
      (is (= [["reflector-p" "target-1"]] @mark-calls*))
      (is (= [["w" "target-1" 20.0 :magic]] @damage-calls*))
      (is (= "ctx-r" (ffirst @fx-calls*))))))

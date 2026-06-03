(ns cn.li.ac.content.ability.electromaster.thunder-bolt-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.ability.registry.skill :as skill-registry]
            [cn.li.ac.content.ability :as ability-content]
            [cn.li.ac.content.ability.electromaster.thunder-bolt :as thunder-bolt]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.mcmod.config.registry :as config-reg]
            [cn.li.ac.ability.effects.geom :as geom]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.potion-effects :as potion-effects]))

(defn- stub-lerp-double [_skill-id field-id exp]
  (case field-id
    :combat.direct-damage (+ 10.0 (* 15.0 exp))
    :combat.aoe-damage (+ 6.0 (* 9.0 exp))
    :cost.down.cp (+ 280.0 (* 140.0 exp))
    :cost.down.overload (- 50.0 (* 23.0 exp))
    0.0))

(defn- stub-lerp-int [_skill-id field-id exp]
  (case field-id
    :cooldown.ticks (int (- 120.0 (* 70.0 exp)))
    1))

(defn- stub-double [_skill-id field-id]
  (case field-id
    :targeting.range 20.0
    :combat.aoe-radius 8.0
    :effect.slowness-exp-threshold 0.2
    :progression.exp-effective 0.005
    :progression.exp-ineffective 0.003
    0.0))

(defn- stub-int [_skill-id field-id]
  (case field-id
    :effect.slowness-duration-ticks 40
    :effect.slowness-amplifier 3
    0))

(defn- with-config-registry [f]
  (let [descriptors (config-reg/get-descriptor-registry)
        values (config-reg/get-value-registry)]
    (try
      (config-reg/set-descriptor-registry! {})
      (config-reg/set-value-registry! {})
      (f)
      (finally
        (config-reg/set-descriptor-registry! descriptors)
        (config-reg/set-value-registry! values)))))

(defn- seed-electromaster-config! [values]
  (let [domain (skill-config/category-domain :electromaster)]
    (config-reg/register-config-descriptors!
      domain
      (get skill-config/descriptors-by-category :electromaster))
    (config-reg/ensure-default-values!
      domain
      (get skill-config/default-values-by-category :electromaster))
    (config-reg/set-config-values! domain values)))

(deftest thunder-bolt-public-spec-uses-tunables-test
  (testing "ThunderBolt public skill spec should consume runtime tunables"
    (with-config-registry
      (fn []
        (ability-content/init-ability-content!)
        (seed-electromaster-config!
          {(skill-config/config-key :thunder-bolt :cost.down.cp) [100.0 220.0]
           (skill-config/config-key :thunder-bolt :cost.down.overload) [10.0 40.0]
           (skill-config/config-key :thunder-bolt :cooldown.ticks) [90.0 30.0]})
        (let [spec (skill-registry/get-skill :thunder-bolt)
              cp-fn (get-in spec [:cost :down :cp])
              overload-fn (get-in spec [:cost :down :overload])
              cooldown-fn (:cooldown-ticks spec)]
          (is (= 160.0 (cp-fn {:exp 0.5})))
          (is (= 25.0 (overload-fn {:exp 0.5})))
          (is (= 60.0 (double (cooldown-fn {:exp 0.5})))))))))

(deftest miss-sends-fallback-fx-and-grants-ineffective-exp-test
  (let [fx* (atom [])
        exp* (atom [])
        cooldown* (atom [])
        lightning* (atom [])
        damage* (atom [])]
    (with-redefs [raycast/*raycast* :mock
                  world-effects/*world-effects* :mock
                  entity-damage/*entity-damage* :mock
                  potion-effects/*potion-effects* :mock
                  geom/world-id-of (fn [_] "w")
                  geom/eye-pos (fn [_] {:x 0.0 :y 64.0 :z 0.0})
                  raycast/get-player-look-vector (fn [_ _] {:x 0.0 :y 0.0 :z 1.0})
                  raycast/raycast-combined (fn [& _] nil)
                  world-effects/find-entities-in-radius (fn [& _] [])
                  world-effects/spawn-lightning! (fn [& args]
                                                   (swap! lightning* conj args)
                                                   true)
                  entity-damage/apply-direct-damage! (fn [& args]
                                                       (swap! damage* conj args)
                                                       true)
                  skill-effects/add-skill-exp! (fn [& args]
                                                 (swap! exp* conj args)
                                                 nil)
                  skill-effects/set-main-cooldown! (fn [& args]
                                                     (swap! cooldown* conj args)
                                                     nil)
                  skill-config/lerp-double stub-lerp-double
                  skill-config/lerp-int stub-lerp-int
                  skill-config/tunable-double stub-double
                  skill-config/tunable-int stub-int
                  skill-config/probability (fn [& _] 1.0)
                  ctx/ctx-send-to-client! (fn [ctx-id channel payload]
                                            (swap! fx* conj [ctx-id channel payload])
                                            nil)]
      (thunder-bolt/thunder-bolt-perform! {:player-id "p1" :ctx-id "ctx-1" :exp 0.5})
      (is (empty? @damage*))
      (is (empty? @lightning*))
      (is (= 1 (count @fx*)))
      (let [[_ _ payload] (first @fx*)]
        (is (= :miss (:hit-kind payload)))
        (is (= {:x 0.0 :y 64.0 :z 20.0} (:end payload)))
        (is (= "p1" (:source-player-id payload)))
        (is (= "w" (:world-id payload))))
      (is (= [["p1" :thunder-bolt 0.003]] @exp*))
      (is (= [["p1" :thunder-bolt 85]] @cooldown*)))))

(deftest entity-hit-applies-direct-and-aoe-without-double-hit-test
  (let [damage* (atom [])
        exp* (atom [])
        fx* (atom [])
        potion* (atom [])
        lightning* (atom [])]
    (with-redefs [raycast/*raycast* :mock
                  world-effects/*world-effects* :mock
                  entity-damage/*entity-damage* :mock
                  potion-effects/*potion-effects* :mock
                  geom/world-id-of (fn [_] "w")
                  geom/eye-pos (fn [_] {:x 1.0 :y 66.0 :z 1.0})
                  raycast/get-player-look-vector (fn [_ _] {:x 0.0 :y 0.0 :z 1.0})
                  raycast/raycast-combined (fn [& _]
                                             {:hit-type :entity
                                              :uuid "mob-1"
                                              :x 10.0 :y 64.0 :z 10.0
                                              :eye-height 1.8})
                  world-effects/spawn-lightning! (fn [& args]
                                                   (swap! lightning* conj args)
                                                   true)
                  world-effects/find-entities-in-radius (fn [& _]
                                                          [{:uuid "mob-1" :x 10.0 :y 64.0 :z 10.0}
                                                           {:uuid "mob-2" :x 10.5 :y 64.0 :z 9.5 :eye-height 1.6}])
                  entity-damage/apply-direct-damage! (fn [_ _world-id target-id damage _]
                                                       (swap! damage* conj [target-id damage])
                                                       true)
                  potion-effects/apply-potion-effect! (fn [& args]
                                                        (swap! potion* conj args)
                                                        nil)
                  skill-effects/add-skill-exp! (fn [& args]
                                                 (swap! exp* conj args)
                                                 nil)
                  skill-effects/set-main-cooldown! (fn [& _] nil)
                  skill-config/lerp-double stub-lerp-double
                  skill-config/lerp-int stub-lerp-int
                  skill-config/tunable-double stub-double
                  skill-config/tunable-int stub-int
                  skill-config/probability (fn [& _] 1.0)
                  ctx/ctx-send-to-client! (fn [ctx-id channel payload]
                                            (swap! fx* conj [ctx-id channel payload])
                                            nil)
                  rand (fn [] 0.0)]
      (thunder-bolt/thunder-bolt-perform! {:player-id "p2" :ctx-id "ctx-2" :exp 0.6})
      (is (= 2 (count @damage*)))
      (is (= 1 (count (filter #(= "mob-1" (first %)) @damage*))))
      (is (= 1 (count (filter #(= "mob-2" (first %)) @damage*))))
      (is (= 1 (count @potion*)))
      (is (= ["p2" :thunder-bolt 0.005] (first @exp*)))
      (is (= 1 (count @lightning*)))
      (let [[_ _ payload] (first @fx*)]
        (is (= :entity (:hit-kind payload)))
        (is (= 1 (count (:aoe-points payload))))))))

(deftest block-hit-applies-aoe-and-effective-exp-test
  (let [damage* (atom [])
        exp* (atom [])
        potion* (atom [])
        lightning* (atom [])]
    (with-redefs [raycast/*raycast* :mock
                  world-effects/*world-effects* :mock
                  entity-damage/*entity-damage* :mock
                  potion-effects/*potion-effects* :mock
                  geom/world-id-of (fn [_] "w")
                  geom/eye-pos (fn [_] {:x 2.0 :y 64.0 :z 2.0})
                  raycast/get-player-look-vector (fn [_ _] {:x 0.0 :y 0.0 :z 1.0})
                  raycast/raycast-combined (fn [& _]
                                             {:hit-type :block
                                              :hit-x 8.0 :hit-y 65.0 :hit-z 8.0
                                              :x 8.0 :y 65.0 :z 8.0})
                  world-effects/spawn-lightning! (fn [& args]
                                                   (swap! lightning* conj args)
                                                   true)
                  world-effects/find-entities-in-radius (fn [& _]
                                                          [{:uuid "mob-a" :x 8.0 :y 65.0 :z 8.0 :eye-height 1.2}
                                                           {:uuid "mob-b" :x 9.0 :y 65.0 :z 8.0 :eye-height 1.4}])
                  entity-damage/apply-direct-damage! (fn [_ _world-id target-id damage _]
                                                       (swap! damage* conj [target-id damage])
                                                       true)
                  potion-effects/apply-potion-effect! (fn [& args]
                                                        (swap! potion* conj args)
                                                        nil)
                  skill-effects/add-skill-exp! (fn [& args]
                                                 (swap! exp* conj args)
                                                 nil)
                  skill-effects/set-main-cooldown! (fn [& _] nil)
                  skill-config/lerp-double stub-lerp-double
                  skill-config/lerp-int stub-lerp-int
                  skill-config/tunable-double stub-double
                  skill-config/tunable-int stub-int
                  skill-config/probability (fn [& _] 1.0)
                  ctx/ctx-send-to-client! (fn [& _] nil)]
      (thunder-bolt/thunder-bolt-perform! {:player-id "p3" :ctx-id "ctx-3" :exp 0.4})
      (is (= 2 (count @damage*)))
      (is (empty? @potion*))
      (is (= ["p3" :thunder-bolt 0.005] (first @exp*)))
      (is (= 1 (count @lightning*))))))

(deftest slowness-requires-exp-threshold-test
  (let [potion* (atom [])]
    (with-redefs [raycast/*raycast* :mock
                  world-effects/*world-effects* :mock
                  entity-damage/*entity-damage* :mock
                  potion-effects/*potion-effects* :mock
                  geom/world-id-of (fn [_] "w")
                  geom/eye-pos (fn [_] {:x 1.0 :y 66.0 :z 1.0})
                  raycast/get-player-look-vector (fn [_ _] {:x 0.0 :y 0.0 :z 1.0})
                  raycast/raycast-combined (fn [& _]
                                             {:hit-type :entity
                                              :uuid "mob-low"
                                              :x 10.0 :y 64.0 :z 10.0
                                              :eye-height 1.8})
                  world-effects/spawn-lightning! (fn [& _] true)
                  world-effects/find-entities-in-radius (fn [& _] [])
                  entity-damage/apply-direct-damage! (fn [& _] true)
                  potion-effects/apply-potion-effect! (fn [& args]
                                                        (swap! potion* conj args)
                                                        nil)
                  skill-effects/add-skill-exp! (fn [& _] nil)
                  skill-effects/set-main-cooldown! (fn [& _] nil)
                  skill-config/lerp-double stub-lerp-double
                  skill-config/lerp-int stub-lerp-int
                  skill-config/tunable-double stub-double
                  skill-config/tunable-int stub-int
                  skill-config/probability (fn [& _] 1.0)
                  ctx/ctx-send-to-client! (fn [& _] nil)
                  rand (fn [] 0.0)]
      (thunder-bolt/thunder-bolt-perform! {:player-id "p4" :ctx-id "ctx-4" :exp 0.1})
      (is (empty? @potion*)))))


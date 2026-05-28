(ns cn.li.ac.content.ability.vecmanip.vec-reflection-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.server.service.skill-effects]
            [cn.li.ac.ability.util.toggle]
            [cn.li.ac.ability.server.damage.handler :as damage-handler]
            [cn.li.ac.content.ability.vecmanip.vec-reflection :as vr]
            [cn.li.ac.content.ability.vecmanip.arbitration]
            [cn.li.mcmod.platform.entity-damage]
            [cn.li.mcmod.platform.entity-motion]
            [cn.li.mcmod.platform.raycast]
            [cn.li.mcmod.platform.world-effects]
            [cn.li.ac.ability.service.dispatcher :as ctx]))

(defn- with-fresh-reflection-runtime [f]
  (damage-handler/reset-attack-check-registries-for-test!)
  (vr/call-with-vec-reflection-runtime
    (vr/create-vec-reflection-runtime)
    (fn []
      (try
        (f)
        (finally
          (damage-handler/reset-attack-check-registries-for-test!)
          (vr/reset-reflection-runtime-for-test!))))))

(use-fixtures :each with-fresh-reflection-runtime)

(def ^:private test-context-owner {:logical-side :server :session-id :test-session})

(deftest send-fx-reflect-entity-includes-reflected-flag-test
  (let [payload* (atom nil)]
    (with-redefs [ctx/ctx-send-to-client! (fn [_ctx-id _ch payload]
                                            (reset! payload* payload))]
      (@#'cn.li.ac.content.ability.vecmanip.vec-reflection/send-fx-reflect-entity!
       "ctx-1"
       {:x 1.0 :y 2.0 :z 3.0 :height 1.8}))
    (is (= true (:reflected? @payload*)))
    (is (= :reflect-entity (:mode @payload*)))))

(deftest init-registers-precheck-side-effect-test
  (let [reflect-calls (atom [])]
    (with-redefs [vr/can-cancel-attack? (fn [_ _ _] true)
                  vr/reflect-damage (fn [player-id attacker-id damage]
                                      (swap! reflect-calls conj [player-id attacker-id damage])
                                      [true 0.0])]
      (vr/init!)
      (is (true? (damage-handler/run-attack-precheck-side-effects! "p" "a" 7.0 :src)))
      (is (= [["p" "a" 7.0]] @reflect-calls)))))

(deftest precheck-side-effect-skips-when-cannot-cancel-test
  (let [reflect-calls (atom 0)]
    (with-redefs [vr/can-cancel-attack? (fn [_ _ _] false)
                  vr/reflect-damage (fn [_ _ _]
                                      (swap! reflect-calls inc)
                                      [true 0.0])]
      (vr/init!)
      (is (false? (damage-handler/run-attack-precheck-side-effects! "p" "a" 7.0 :src)))
      (is (= 0 @reflect-calls)))))

(deftest can-cancel-attack-precheck-threshold-test
  (with-redefs [cn.li.ac.content.ability.vecmanip.vec-reflection/active-vec-reflection-ctx-id (fn [_] "ctx-1")
                cn.li.ac.content.ability.vecmanip.vec-reflection/skill-exp (fn [_] 0.5)
                cn.li.ac.content.ability.vecmanip.vec-reflection/current-cp (fn [_] 3.0)
                cn.li.ac.content.ability.vecmanip.vec-reflection/cfg-lerp (fn [field _exp]
                                                                            (case field
                                                                              :cost.damage.cp 2.0
                                                                              :combat.damage-multiplier 1.0
                                                                              0.0))
                cn.li.ac.content.ability.vecmanip.vec-reflection/cfg-double (fn [field]
                                                                              (case field
                                                                                :combat.min-reflected-damage 5.0
                                                                                0.0))
                cn.li.ac.ability.server.service.skill-effects/get-player-state (fn [_] {:ok true})]
    ;; damage=2 -> consumption=4, current-cp=3 => false
    (is (false? (vr/can-cancel-attack? "p" "a" 2.0)))
      (with-redefs [cn.li.ac.content.ability.vecmanip.vec-reflection/current-cp (fn [_] 15.0)]
        ;; damage=6 -> consumption=12, current-cp=15 >= 12, reflected=6 >= min(5) => true
      (is (true? (vr/can-cancel-attack? "p" "a" 6.0))))))

(deftest visited-map-prune-ttl-and-max-size-test
  (let [now 1000
        ttl-ms 200
        max-size 2
        input {"old" 600 "keep-a" 900 "keep-b" 950 "keep-c" 990}
        out (@#'cn.li.ac.content.ability.vecmanip.vec-reflection/prune-visited-map
             input now ttl-ms max-size)]
    ;; old expired entry removed
    (is (not (contains? out "old")))
    ;; capped to max-size and keeps newest timestamps
    (is (= 2 (count out)))
    (is (contains? out "keep-c"))
    (is (contains? out "keep-b"))))

(deftest reflect-damage-recursion-guard-pair-level-test
  (let [applied (atom [])]
    (with-redefs [cn.li.ac.ability.server.service.skill-effects/get-player-state (fn [_] {:position {:world-id "w"}})
                  cn.li.ac.content.ability.vecmanip.vec-reflection/skill-exp (fn [_] 0.5)
                  cn.li.ac.content.ability.vecmanip.vec-reflection/current-cp (fn [_] 9999.0)
                  cn.li.ac.content.ability.vecmanip.vec-reflection/consume-cp! (fn [_ _] true)
                  cn.li.ac.content.ability.vecmanip.vec-reflection/cfg-lerp (fn [field _]
                                                                              (case field
                                                                                :combat.damage-multiplier 1.0
                                                                                :cost.damage.cp 0.0
                                                                                0.0))
                  cn.li.ac.content.ability.vecmanip.vec-reflection/cfg-double (fn [field]
                                                                                (case field
                                                                                  :combat.min-reflected-damage 0.0
                                                                                  :progression.exp-damage-scale 0.0
                                                                                  0.0))
                  cn.li.ac.content.ability.vecmanip.vec-reflection/max-reflections (fn [] 6)
                  cn.li.mcmod.platform.entity-damage/*entity-damage* :mock
                  cn.li.mcmod.platform.entity-damage/apply-direct-damage! (fn [_ world-id attacker-id damage _]
                                                                            (swap! applied conj [world-id attacker-id damage]))
                  cn.li.ac.content.ability.vecmanip.vec-reflection/add-exp! (fn [_ _] nil)
                  cn.li.ac.content.ability.vecmanip.vec-reflection/active-vec-reflection-ctx-id (fn [_] nil)]
      (binding [vr/*reflection-chain-id* "chain-guard"]
        (vr/mark-reflecting-for-test! "p" "a" nil "chain-guard")
        (is (= [false 10.0] (vr/reflect-damage "p" "a" 10.0))))
      (is (empty? @applied)))))

(deftest reflect-damage-stops-when-max-reflections-reached-test
  (let [applied (atom [])]
    (with-redefs [cn.li.ac.ability.server.service.skill-effects/get-player-state (fn [_] {:position {:world-id "w"}})
                  cn.li.ac.content.ability.vecmanip.vec-reflection/skill-exp (fn [_] 0.5)
                  cn.li.ac.content.ability.vecmanip.vec-reflection/current-cp (fn [_] 9999.0)
                  cn.li.ac.content.ability.vecmanip.vec-reflection/consume-cp! (fn [_ _] true)
                  cn.li.ac.content.ability.vecmanip.vec-reflection/cfg-lerp (fn [field _]
                                                                              (case field
                                                                                :combat.damage-multiplier 1.0
                                                                                :cost.damage.cp 0.0
                                                                                0.0))
                  cn.li.ac.content.ability.vecmanip.vec-reflection/cfg-double (fn [field]
                                                                                (case field
                                                                                  :combat.min-reflected-damage 0.0
                                                                                  :progression.exp-damage-scale 0.0
                                                                                  0.0))
                  cn.li.ac.content.ability.vecmanip.vec-reflection/max-reflections (fn [] 2)
                  cn.li.mcmod.platform.entity-damage/*entity-damage* :mock
                  cn.li.mcmod.platform.entity-damage/apply-direct-damage! (fn [_ world-id attacker-id damage _]
                                                                            (swap! applied conj [world-id attacker-id damage]))
                  cn.li.ac.content.ability.vecmanip.vec-reflection/add-exp! (fn [_ _] nil)
                  cn.li.ac.content.ability.vecmanip.vec-reflection/active-vec-reflection-ctx-id (fn [_] nil)]
      (binding [vr/*reflection-chain-id* "chain-depth"]
        (vr/set-reflection-depth-for-test! "p" "a" nil "chain-depth" 3)
        (is (= [false 10.0] (vr/reflect-damage "p" "a" 10.0))))
      (is (empty? @applied)))))

(deftest reflect-damage-recursion-state-isolated-by-context-test
  (let [applied (atom [])]
    (with-redefs [cn.li.ac.ability.server.service.skill-effects/get-player-state (fn [_] {:position {:world-id "w"}})
                  cn.li.ac.content.ability.vecmanip.vec-reflection/skill-exp (fn [_] 0.5)
                  cn.li.ac.content.ability.vecmanip.vec-reflection/current-cp (fn [_] 9999.0)
                  cn.li.ac.content.ability.vecmanip.vec-reflection/consume-cp! (fn [_ _] true)
                  cn.li.ac.content.ability.vecmanip.vec-reflection/cfg-lerp (fn [field _]
                                                                              (case field
                                                                                :combat.damage-multiplier 1.0
                                                                                :cost.damage.cp 0.0
                                                                                0.0))
                  cn.li.ac.content.ability.vecmanip.vec-reflection/cfg-double (fn [field]
                                                                                (case field
                                                                                  :combat.min-reflected-damage 0.0
                                                                                  :progression.exp-damage-scale 0.0
                                                                                  0.0))
                  cn.li.ac.content.ability.vecmanip.vec-reflection/max-reflections (fn [] 6)
                  cn.li.mcmod.platform.entity-damage/*entity-damage* :mock
                  cn.li.mcmod.platform.entity-damage/apply-direct-damage! (fn [_ world-id attacker-id damage _]
                                                                            (swap! applied conj [world-id attacker-id damage]))
                  cn.li.ac.content.ability.vecmanip.vec-reflection/add-exp! (fn [_ _] nil)
                  cn.li.ac.content.ability.vecmanip.vec-reflection/active-vec-reflection-ctx-id (fn [_] "ctx-current")]
      (binding [vr/*reflection-chain-id* "chain-shared"]
        (vr/mark-reflecting-for-test! "p" "a" "ctx-other" "chain-shared")
        (binding [ctx/*context-owner* test-context-owner]
          (is (= [true 0.0] (vr/reflect-damage "p" "a" 10.0)))))
      (is (= [["w" "a" 10.0]] @applied)))))

(deftest reflect-damage-recursion-state-isolated-by-chain-test
  (let [applied (atom [])]
    (with-redefs [cn.li.ac.ability.server.service.skill-effects/get-player-state (fn [_] {:position {:world-id "w"}})
                  cn.li.ac.content.ability.vecmanip.vec-reflection/skill-exp (fn [_] 0.5)
                  cn.li.ac.content.ability.vecmanip.vec-reflection/current-cp (fn [_] 9999.0)
                  cn.li.ac.content.ability.vecmanip.vec-reflection/consume-cp! (fn [_ _] true)
                  cn.li.ac.content.ability.vecmanip.vec-reflection/cfg-lerp (fn [field _]
                                                                              (case field
                                                                                :combat.damage-multiplier 1.0
                                                                                :cost.damage.cp 0.0
                                                                                0.0))
                  cn.li.ac.content.ability.vecmanip.vec-reflection/cfg-double (fn [field]
                                                                                (case field
                                                                                  :combat.min-reflected-damage 0.0
                                                                                  :progression.exp-damage-scale 0.0
                                                                                  0.0))
                  cn.li.ac.content.ability.vecmanip.vec-reflection/max-reflections (fn [] 6)
                  cn.li.mcmod.platform.entity-damage/*entity-damage* :mock
                  cn.li.mcmod.platform.entity-damage/apply-direct-damage! (fn [_ world-id attacker-id damage _]
                                                                            (swap! applied conj [world-id attacker-id damage]))
                  cn.li.ac.content.ability.vecmanip.vec-reflection/add-exp! (fn [_ _] nil)
                  cn.li.ac.content.ability.vecmanip.vec-reflection/active-vec-reflection-ctx-id (fn [_] "ctx-current")]
      (vr/mark-reflecting-for-test! "p" "a" "ctx-current" "chain-other")
      (binding [vr/*reflection-chain-id* "chain-current"]
        (binding [ctx/*context-owner* test-context-owner]
          (is (= [true 0.0] (vr/reflect-damage "p" "a" 10.0)))))
      (is (= [["w" "a" 10.0]] @applied)))))

(deftest vec-reflection-runtime-isolation-test
  (let [runtime-a (vr/create-vec-reflection-runtime)
        runtime-b (vr/create-vec-reflection-runtime)]
    (vr/call-with-vec-reflection-runtime
      runtime-a
      (fn []
        (vr/mark-reflecting-for-test! "p" "a" "ctx-a" "chain-a")
        (vr/set-reflection-depth-for-test! "p" "a" "ctx-a" "chain-a" 2)
        (is (= 1 (count (:reflecting-pairs (vr/reflection-runtime-snapshot)))))
        (is (= 2 (get-in (vr/reflection-runtime-snapshot)
                         [:reflection-depths (vr/reflection-owner-key "p" "a" "ctx-a" "chain-a")])))))
    (vr/call-with-vec-reflection-runtime
      runtime-b
      (fn []
        (is (= {:reflecting-pairs #{}
                :reflection-depths {}}
               (vr/reflection-runtime-snapshot)))
        (vr/mark-reflecting-for-test! "p" "a" "ctx-a" "chain-a")
        (vr/set-reflection-depth-for-test! "p" "a" "ctx-a" "chain-a" 5)
        (is (= 5 (get-in (vr/reflection-runtime-snapshot)
                         [:reflection-depths (vr/reflection-owner-key "p" "a" "ctx-a" "chain-a")])))))
    (vr/call-with-vec-reflection-runtime
      runtime-a
      (fn []
        (is (= 2 (get-in (vr/reflection-runtime-snapshot)
                         [:reflection-depths (vr/reflection-owner-key "p" "a" "ctx-a" "chain-a")])))))))

(deftest vec-reflection-fallback-works-without-binding-test
  (binding [vr/*vec-reflection-runtime* nil]
    (vr/reset-reflection-runtime-for-test!)
    (vr/mark-reflecting-for-test! "p" "a" "ctx-fallback" "chain-fallback")
    (is (= 1 (count (:reflecting-pairs (vr/reflection-runtime-snapshot)))))
    (is (contains? (:reflecting-pairs (vr/reflection-runtime-snapshot))
                   (vr/reflection-owner-key "p" "a" "ctx-fallback" "chain-fallback")))))

(deftest tick-reflect-fireball-spawn-and-discard-test
  (let [spawn-calls (atom [])
        discard-calls (atom [])
        fx-calls (atom 0)]
    (with-redefs [ctx/get-context (fn [_]
                                    {:skill-state {:toggle {:vec-reflection {:active true}}
                                                   :vec-reflection-overload-keep 0.0
                                                   :vec-reflection-visited-map {}}})
                  ctx/update-context! (fn [& _] nil)
                  cn.li.ac.ability.util.toggle/is-toggle-active? (fn [_ _] true)
                  cn.li.ac.ability.util.toggle/update-toggle-tick! (fn [& _] nil)
                  cn.li.ac.content.ability.vecmanip.vec-reflection/enforce-overload-floor! (fn [& _] nil)
                  cn.li.ac.content.ability.vecmanip.vec-reflection/skill-exp (fn [_] 0.5)
                  cn.li.ac.content.ability.vecmanip.vec-reflection/get-player-position (fn [_]
                                                                                          {:world-id "w" :x 0.0 :y 64.0 :z 0.0})
                  cn.li.ac.content.ability.vecmanip.vec-reflection/cfg-double (fn [field]
                                                                                (case field
                                                                                  :targeting.radius 6.0
                                                                                  :progression.exp-reflect-entity-scale 0.0
                                                                                  0.0))
                  cn.li.ac.content.ability.vecmanip.vec-reflection/cfg-lerp (fn [field _]
                                                                              (case field
                                                                                :cost.reflect-entity.cp 0.0
                                                                                0.0))
                  cn.li.ac.content.ability.vecmanip.vec-reflection/consume-cp! (fn [& _] true)
                  cn.li.ac.content.ability.vecmanip.vec-reflection/large-fireball-ids (fn [] #{"minecraft:fireball" "minecraft:large_fireball"})
                  cn.li.ac.content.ability.vecmanip.vec-reflection/small-fireball-ids (fn [] #{"minecraft:small_fireball"})
                  cn.li.ac.content.ability.vecmanip.vec-reflection/affected-entity-difficulty (fn [] {"minecraft:fireball" 1.0})
                  cn.li.ac.content.ability.vecmanip.vec-reflection/excluded-entity-ids (fn [] #{})
                  cn.li.ac.content.ability.vecmanip.vec-reflection/add-exp! (fn [& _] nil)
                  cn.li.ac.content.ability.vecmanip.vec-reflection/send-fx-reflect-entity! (fn [& _] (swap! fx-calls inc))
                  cn.li.ac.content.ability.vecmanip.arbitration/dual-active? (fn [_] false)
                  cn.li.ac.content.ability.vecmanip.arbitration/claim-projectile! (fn [& _] true)
                  cn.li.mcmod.platform.world-effects/find-entities-in-radius (fn [& _]
                                                                               [{:uuid "e1"
                                                                                 :entity-id "minecraft:fireball"
                                                                                 :x 1.0 :y 65.0 :z 1.0}])
                  cn.li.mcmod.platform.world-effects/spawn-projectile! (fn [_ world-id spec]
                                                                         (swap! spawn-calls conj [world-id spec])
                                                                         {:success? true :uuid "spawned" :entity-id (:entity-id spec)})
                  cn.li.mcmod.platform.raycast/get-player-look-vector (fn [& _] {:x 1.0 :y 0.0 :z 0.0})
                  cn.li.mcmod.platform.entity-motion/get-velocity (fn [& _] {:x 1.0 :y 0.0 :z 0.0})
                  cn.li.mcmod.platform.entity-motion/discard-entity! (fn [_ world-id entity-id]
                                                                       (swap! discard-calls conj [world-id entity-id]))]
      (binding [cn.li.mcmod.platform.world-effects/*world-effects* :mock
                cn.li.mcmod.platform.entity-motion/*entity-motion* :mock
                cn.li.mcmod.platform.raycast/*raycast* :mock]
        (@#'cn.li.ac.content.ability.vecmanip.vec-reflection/vec-reflection-on-key-tick-body
         "p1" "ctx-1" true))
      (is (= 1 (count @spawn-calls)))
      (let [[world-id spec] (first @spawn-calls)]
        (is (= "w" world-id))
        (is (= "minecraft:fireball" (:entity-id spec)))
        (is (= "p1" (:owner-uuid spec)))
        (is (= 1.0 (:vx spec)))
        (is (= 0.0 (:vy spec)))
        (is (= 0.0 (:vz spec))))
      (is (= [["w" "e1"]] @discard-calls))
      (is (= 1 @fx-calls)))))

(deftest tick-reflect-fireball-spawn-fallback-to-set-velocity-test
  (let [spawn-calls (atom [])
        set-velocity-calls (atom [])
        discard-calls (atom [])]
    (with-redefs [ctx/get-context (fn [_]
                                    {:skill-state {:toggle {:vec-reflection {:active true}}
                                                   :vec-reflection-overload-keep 0.0
                                                   :vec-reflection-visited-map {}}})
                  ctx/update-context! (fn [& _] nil)
                  cn.li.ac.ability.util.toggle/is-toggle-active? (fn [_ _] true)
                  cn.li.ac.ability.util.toggle/update-toggle-tick! (fn [& _] nil)
                  cn.li.ac.content.ability.vecmanip.vec-reflection/enforce-overload-floor! (fn [& _] nil)
                  cn.li.ac.content.ability.vecmanip.vec-reflection/skill-exp (fn [_] 0.5)
                  cn.li.ac.content.ability.vecmanip.vec-reflection/get-player-position (fn [_]
                                                                                          {:world-id "w" :x 0.0 :y 64.0 :z 0.0})
                  cn.li.ac.content.ability.vecmanip.vec-reflection/cfg-double (fn [field]
                                                                                (case field
                                                                                  :targeting.radius 6.0
                                                                                  :progression.exp-reflect-entity-scale 0.0
                                                                                  0.0))
                  cn.li.ac.content.ability.vecmanip.vec-reflection/cfg-lerp (fn [field _]
                                                                              (case field
                                                                                :cost.reflect-entity.cp 0.0
                                                                                0.0))
                  cn.li.ac.content.ability.vecmanip.vec-reflection/consume-cp! (fn [& _] true)
                  cn.li.ac.content.ability.vecmanip.vec-reflection/large-fireball-ids (fn [] #{"minecraft:fireball" "minecraft:large_fireball"})
                  cn.li.ac.content.ability.vecmanip.vec-reflection/small-fireball-ids (fn [] #{"minecraft:small_fireball"})
                  cn.li.ac.content.ability.vecmanip.vec-reflection/affected-entity-difficulty (fn [] {"minecraft:fireball" 1.0})
                  cn.li.ac.content.ability.vecmanip.vec-reflection/excluded-entity-ids (fn [] #{})
                  cn.li.ac.content.ability.vecmanip.vec-reflection/add-exp! (fn [& _] nil)
                  cn.li.ac.content.ability.vecmanip.vec-reflection/send-fx-reflect-entity! (fn [& _] nil)
                  cn.li.ac.content.ability.vecmanip.arbitration/dual-active? (fn [_] false)
                  cn.li.ac.content.ability.vecmanip.arbitration/claim-projectile! (fn [& _] true)
                  cn.li.mcmod.platform.world-effects/find-entities-in-radius (fn [& _]
                                                                               [{:uuid "e1"
                                                                                 :entity-id "minecraft:fireball"
                                                                                 :x 1.0 :y 65.0 :z 1.0}])
                  cn.li.mcmod.platform.world-effects/spawn-projectile! (fn [_ world-id spec]
                                                                         (swap! spawn-calls conj [world-id spec])
                                                                         {:success? false})
                  cn.li.mcmod.platform.raycast/get-player-look-vector (fn [& _] {:x 0.0 :y 1.0 :z 0.0})
                  cn.li.mcmod.platform.entity-motion/get-velocity (fn [& _] {:x 0.0 :y 0.0 :z 2.0})
                  cn.li.mcmod.platform.entity-motion/set-velocity! (fn [_ world-id entity-id vx vy vz]
                                                                     (swap! set-velocity-calls conj [world-id entity-id vx vy vz]))
                  cn.li.mcmod.platform.entity-motion/discard-entity! (fn [_ world-id entity-id]
                                                                       (swap! discard-calls conj [world-id entity-id]))]
      (binding [cn.li.mcmod.platform.world-effects/*world-effects* :mock
                cn.li.mcmod.platform.entity-motion/*entity-motion* :mock
                cn.li.mcmod.platform.raycast/*raycast* :mock]
        (@#'cn.li.ac.content.ability.vecmanip.vec-reflection/vec-reflection-on-key-tick-body
         "p1" "ctx-1" true))
      (is (= 1 (count @spawn-calls)))
      (is (= [["w" "e1" 0.0 2.0 0.0]] @set-velocity-calls))
      (is (empty? @discard-calls)))))

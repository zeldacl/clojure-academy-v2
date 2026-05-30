(ns cn.li.ac.content.ability.meltdowner.projectile-mark-integration-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.model.ability :as ad]
            [cn.li.ac.ability.server.damage.runtime :as rt]
            [cn.li.ac.ability.service.delayed-projectiles :as dp]
            [cn.li.ac.ability.service.dispatcher :as ctx]
            [cn.li.ac.ability.service.player-state :as ps]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.server.effect.geom :as geom]
            [cn.li.ac.content.ability.meltdowner.damage-helper :as dh]
            [cn.li.ac.content.ability.meltdowner.electron-missile :as missile]
            [cn.li.ac.content.ability.meltdowner.rad-intensify :as rad]
            [cn.li.ac.test.support.player-state :as ps-fix]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.ac.ability.service.context-mgr :as ctx-mgr]))

(defn- with-fresh-meltdowner-runtimes [f]
  (let [player-state-rt (ps/create-player-state-runtime)
        projectile-rt (dp/create-delayed-projectile-runtime)
        damage-helper-rt (dh/create-damage-helper-runtime)
        damage-registry-rt (rt/create-damage-handler-registry-runtime)]
    (ps/call-with-player-state-runtime player-state-rt
      (fn []
        (ps-fix/with-test-player-state-owner
          (fn []
            (dp/call-with-delayed-projectile-runtime projectile-rt
              (fn []
                (dh/call-with-damage-helper-runtime damage-helper-rt
                  (fn []
                    (rt/call-with-damage-handler-registry-runtime damage-registry-rt
                      (fn []
                        (ps/reset-player-states-for-test!)
                        (rt/reset-damage-handler-registry-for-test!)
                        (try
                          (f)
                          (finally
                            (dp/reset-pending-tasks-for-test!)
                            (dh/reset-marks-for-test!)
                            (rt/reset-damage-handler-registry-for-test!)
                            (ps/reset-player-states-for-test!)))))))))))))))

(use-fixtures :each with-fresh-meltdowner-runtimes)

(defn- learned-rad-intensify-data []
  (-> (ad/new-ability-data) (ad/learn-skill :rad-intensify)))

(defn- learn-rad-intensify! [player-id]
  (ps/set-player-state! player-id {:ability-data (learned-rad-intensify-data)}))

(defn- missile-context-mocks [initial]
  (let [ctx* (atom initial)]
    {:ctx* ctx*
     :get-context (fn [_] @ctx*)
     :update-context! (fn [_ f & args]
                        (swap! ctx* #(when % (apply f % args))))}))

(defn- stub-missile-lerp-double [_skill-id field-id _exp]
  (case field-id
    :combat.damage 12.0
    :cost.tick.cp 7.0
    :cost.attack.cp 42.0
    :cost.attack.overload 6.0
    :targeting.seek-range 10.0
    0.0))

(defn- stub-missile-lerp-int [_skill-id field-id _exp]
  (case field-id
    :cooldown.ticks 550
    :charge.max-hold-ticks 80
    0))

(defn- stub-missile-tunable-int [_skill-id field-id]
  (case field-id
    :projectile.max-hold-balls 5
    :timing.spawn-interval-ticks 10
    :timing.fire-interval-ticks 8
    0))

(defn- stub-missile-tunable-double [_skill-id field-id]
  (case field-id
    :cost.down.overload 200.0
    :progression.exp-hit 0.001
    0.0))

(deftest electron-bomb-delayed-hit-installs-rad-mark-test
  (let [attacker "atk-eb"
        victim "victim-eb"]
    (dh/ensure-damage-handler!)
    (learn-rad-intensify! attacker)
    (with-redefs [rad/rate (fn [_] 1.75)
                  rad/mark-duration-ms (fn [] 100000)
                  skill-effects/add-skill-exp! (fn [& _] nil)
                  ctx-mgr/push-channel-to-player! (fn [& _] nil)
                  ctx-mgr/push-channel-to-nearby-players! (fn [& _] nil)]
      (binding [raycast/*raycast* (reify raycast/IRaycast
                                    (raycast-blocks [_ _ _ _ _ _ _ _ _] nil)
                                    (raycast-entities [_ _ _ _ _ _ _ _ _]
                                      {:uuid victim :x 0.0 :y 64.0 :z 8.0 :distance 8.0})
                                    (raycast-combined [_ _ _ _ _ _ _ _ _] nil)
                                    (get-player-look-vector [_ _] {:x 0.0 :y 0.0 :z 1.0})
                                    (raycast-from-player [_ _ _ _] nil))
                entity-damage/*entity-damage* (reify entity-damage/IEntityDamage
                                               (apply-direct-damage! [_ _ _ _ _] true)
                                               (apply-aoe-damage! [_ _ _ _ _ _ _ _ _] [])
                                               (apply-reflection-damage! [_ _ _ _ _ _ _] []))]
        (dp/schedule-electron-bomb-beam!
         {:player-id attacker
          :ctx-id "ctx-eb"
          :world-id "w"
          :eye {:x 0.0 :y 64.0 :z 0.0}
          :look-dir {:x 0.0 :y 0.0 :z 1.0}
          :damage 12.0
          :exp-gain 0.003
          :delay-ticks 1})
        (dp/tick-player! attacker))
      (is (= 1.75 (:rate (get (dh/marks-snapshot) [attacker victim]))))
      (is (= 17.5 (double (rt/process-damage! victim attacker 10.0 :magic)))))))

(deftest electron-missile-hit-installs-rad-mark-test
  (let [attacker "atk-em"
        victim "victim-em"
        {:keys [get-context update-context!]} (missile-context-mocks {:skill-state {:ticks 8 :active-balls 1 :active? true :overload-floor 200.0}})]
    (dh/ensure-damage-handler!)
    (learn-rad-intensify! attacker)
    (with-redefs [rad/rate (fn [_] 1.6)
                  rad/mark-duration-ms (fn [] 100000)
                  skill-effects/skill-exp (fn [& _] 0.5)
                  skill-config/lerp-double stub-missile-lerp-double
                  skill-config/lerp-int stub-missile-lerp-int
                  skill-config/tunable-int stub-missile-tunable-int
                  skill-config/tunable-double stub-missile-tunable-double
                  skill-effects/enforce-overload-floor! (fn [& _] nil)
                  skill-effects/perform-resource! (fn [& _] {:success? true})
                  skill-effects/add-skill-exp! (fn [& _] nil)
                  ctx/get-context get-context
                  ctx/update-context! update-context!
                  ctx/ctx-send-to-client! (fn [& _] nil)
                  ctx/ctx-send-to-except-local! (fn [& _] nil)
                  geom/world-id-of (fn [_] "w")
                  geom/eye-pos (fn [_] {:x 0.0 :y 64.0 :z 0.0})
                  world-effects/find-entities-in-radius (fn [& _]
                                                         [{:uuid victim
                                                           :x 3.0 :y 64.0 :z 0.0
                                                           :eye-height 1.6
                                                           :living? true}])]
      (binding [world-effects/*world-effects* :world
                entity-damage/*entity-damage* (reify entity-damage/IEntityDamage
                                               (apply-direct-damage! [_ _ _ _ _] true)
                                               (apply-aoe-damage! [_ _ _ _ _ _ _ _ _] [])
                                               (apply-reflection-damage! [_ _ _ _ _ _ _] []))]
        (missile/electron-missile-tick! {:player-id attacker
                                         :ctx-id "ctx-em"
                                         :player {:id "player-obj"}}))
      (is (= 1.6 (:rate (get (dh/marks-snapshot) [attacker victim]))))
      (is (= 16.0 (double (rt/process-damage! victim attacker 10.0 :magic)))))))

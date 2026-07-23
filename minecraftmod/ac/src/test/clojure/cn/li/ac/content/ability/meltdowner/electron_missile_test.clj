(ns cn.li.ac.content.ability.meltdowner.electron-missile-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.test.skill-callback-test-helpers :as cb]
            [cn.li.ac.ability.fx :as fx]
            [cn.li.ac.content.ability.meltdowner.electron-missile :as missile]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.context-skill-state :as ctx-skill]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.content.ability.meltdowner.damage-helper :as md-damage]
            [cn.li.ac.ability.effects.geom :as geom]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.world-effects :as world-effects]))

(defn- make-context-mocks [initial]
  (let [ctx* (atom initial)]
    {:ctx* ctx*
     :get-context (fn [_] @ctx*)
     :update-skill-state-root! (fn [_ f & args]
                        (swap! ctx* update :skill-state
                               (fn [ss]
                                 (let [current (or ss {})
                                       next-state (if (and (= f identity) (= 1 (count args)))
                                                    (first args)
                                                    (apply f current args))]
                                   next-state))))}))

(defn- capture-local-and-nearby-fx! [local-fx* nearby-fx*]
  (fn [ctx-id entry _evt payload]
    (case (:to entry)
      :client (swap! local-fx* conj [ctx-id (:topic entry) payload])
      :except-local (swap! nearby-fx* conj [ctx-id (:topic entry) payload]))
    nil))

(defn- stub-lerp-double [_skill-id field-id _exp]
       (case field-id
              :combat.damage 12.0
              :cost.tick.cp 7.0
              :cost.attack.cp 42.0
              :cost.attack.overload 6.0
              :targeting.seek-range 10.0
              0.0))

(defn- stub-lerp-int [_skill-id field-id _exp]
       (case field-id
              :cooldown.ticks 550
              :charge.max-hold-ticks 80
              0))

(defn- stub-tunable-int [_skill-id field-id]
       (case field-id
              :projectile.max-hold-balls 5
              :timing.spawn-interval-ticks 10
              :timing.fire-interval-ticks 8
              0))

(defn- stub-tunable-double [_skill-id field-id]
       (case field-id
              :cost.down.overload 200.0
              :progression.exp-hit 0.001
              0.0))

(deftest electron-missile-down-initializes-state-and-sends-start-fx-test
  (let [{:keys [ctx* update-skill-state-root!]} (make-context-mocks {:skill-state {:legacy true}})
        local-fx* (atom [])
        nearby-fx* (atom [])]
    (with-redefs [skill-effects/get-player-state (fn [_]
                                                    {:resource-data {:cur-overload 350.0}})
                  skill-effects/skill-exp (fn [& _] 0.4)
                  skill-config/tunable-double stub-tunable-double
                  ctx-skill/update-skill-state-root! update-skill-state-root!
                  fx/send! (capture-local-and-nearby-fx! local-fx* nearby-fx*)]
      (cb/apply-invoke missile/electron-missile-down! :player-id "p1" :ctx-id "ctx-1" :cost-ok? true))

    (is (= {:ticks 0 :active-balls 0 :active? true :overload-floor 350.0}
           (:skill-state @ctx*)))
    (is (= [["ctx-1" :electron-missile/fx-start {}]] @local-fx*))
    (is (= [["ctx-1" :electron-missile/fx-start {}]] @nearby-fx*))))

(deftest electron-missile-tick-spawns-ball-on-spawn-interval-test
  (let [{:keys [ctx* get-context update-skill-state-root!]}
        (make-context-mocks {:skill-state {:ticks 0 :active-balls 0 :active? true :overload-floor 220.0}})
        spawn-calls* (atom [])
        local-fx* (atom [])
        nearby-fx* (atom [])
        floor-calls* (atom [])]
    (with-redefs [skill-effects/skill-exp (fn [& _] 0.5)
                  skill-config/lerp-int stub-lerp-int
                  skill-config/tunable-int stub-tunable-int
                  skill-config/tunable-double stub-tunable-double
                  skill-effects/enforce-overload-floor! (fn [player-id floor]
                                                         (swap! floor-calls* conj [player-id floor])
                                                         nil)
                  ctx/get-context get-context
                  ctx-skill/update-skill-state-root! update-skill-state-root!
                  fx/send! (capture-local-and-nearby-fx! local-fx* nearby-fx*)
                  geom/world-id-of (fn [_] "w")
                  geom/eye-pos (fn [_] {:x 1.0 :y 65.0 :z 2.0})
                  entity/player-spawn-entity-by-id! (fn [& args]
                                                      (swap! spawn-calls* conj args)
                                                      true)]
      (cb/apply-invoke missile/electron-missile-tick! :player-id "p1" :ctx-id "ctx-2" :player-ref {:id "player-obj"}))

    (is (= [[{:id "player-obj"} "my_mod:entity_md_ball" 0.0]] @spawn-calls*))
    (is (= ["p1" 220.0] (first @floor-calls*)))
    (is (= {:ticks 1 :active-balls 1 :active? true :overload-floor 220.0}
           (:skill-state @ctx*)))
    (is (= :electron-missile/fx-update (second (first @local-fx*))))
    (is (= {:ticks 0 :balls 1} (nth (first @local-fx*) 2)))
    (is (= @local-fx* @nearby-fx*))))

(deftest electron-missile-tick-fires-immediate-hit-when-target-and-cost-ok-test
  (let [{:keys [ctx* get-context update-skill-state-root!]}
        (make-context-mocks {:skill-state {:ticks 8 :active-balls 1 :active? true :overload-floor 200.0}})
        local-fx* (atom [])
        nearby-fx* (atom [])
        damage-calls* (atom [])
        mark-calls* (atom [])
        exp-calls* (atom [])]
    (with-redefs [skill-effects/skill-exp (fn [& _] 0.5)
                  skill-config/lerp-double stub-lerp-double
                  skill-config/lerp-int stub-lerp-int
                  skill-config/tunable-int stub-tunable-int
                  skill-config/tunable-double stub-tunable-double
                  skill-effects/enforce-overload-floor! (fn [& _] nil)
                  skill-effects/perform-resource! (fn [& _] {:success? true})
                  skill-effects/add-skill-exp! (fn [player-id skill-id amount]
                                                 (swap! exp-calls* conj [player-id skill-id amount])
                                                 nil)
                  ctx/get-context get-context
                  ctx-skill/update-skill-state-root! update-skill-state-root!
                  fx/send! (capture-local-and-nearby-fx! local-fx* nearby-fx*)
                  geom/world-id-of (fn [_] "w")
                  geom/eye-pos (fn [_] {:x 0.0 :y 64.0 :z 0.0})
                  world-effects/available? (constantly true)
                  world-effects/find-entities-in-radius (fn [& _]
                                                         [{:uuid "t-1"
                                                           :x 3.0 :y 64.0 :z 0.0
                                                           :eye-height 1.6
                                                           :living? true}])
                  entity-damage/available? (constantly true)
                  entity-damage/apply-direct-damage! (fn [& args]
                                                       (swap! damage-calls* conj args)
                                                       true)
                  md-damage/mark-target! (fn [player-id uuid fx-context]
                                           (swap! mark-calls* conj [player-id uuid fx-context])
                                           nil)]
      (cb/apply-invoke missile/electron-missile-tick! :player-id "p1" :ctx-id "ctx-3" :player-ref {:id "player-obj"}))

    (is (= 1 (count @damage-calls*)))
    ;; Original resets hurtResistantTime = -1 before each hit; the port asks
    ;; the platform bridge to clear the same invulnerability window.
    (is (= ["w" "t-1" 12.0 :magic {:reset-invulnerable-time? true}]
           (vec (first @damage-calls*))))
    (is (= [["p1" "t-1" {:ctx-id "ctx-3"
                          :target-pos {:x 3.0 :y 64.0 :z 0.0}}]]
           @mark-calls*))
    (is (= [["p1" :electron-missile 0.001]] @exp-calls*))
    (is (= {:ticks 9 :active-balls 0 :active? true :overload-floor 200.0}
           (:skill-state @ctx*)))
    (is (= [:electron-missile/fx-fire :electron-missile/fx-update]
           (mapv second @local-fx*)))
    (is (= @local-fx* @nearby-fx*))))

(deftest electron-missile-up-and-abort-send-end-and-reset-state-test
  (let [{:keys [ctx* update-skill-state-root!]}
        (make-context-mocks {:skill-state {:ticks 22 :active-balls 3 :active? true :overload-floor 200.0}})
        local-fx* (atom [])
        nearby-fx* (atom [])
        cooldown-calls* (atom [])]
    (with-redefs [skill-effects/skill-exp (fn [& _] 0.5)
                  skill-config/lerp-int stub-lerp-int
                  skill-effects/set-main-cooldown! (fn [player-id skill-id ticks]
                                                     (swap! cooldown-calls* conj [player-id skill-id ticks])
                                                     nil)
                  ctx-skill/update-skill-state-root! update-skill-state-root!
                  fx/send! (capture-local-and-nearby-fx! local-fx* nearby-fx*)]
      (cb/apply-invoke missile/electron-missile-up! :player-id "p1" :ctx-id "ctx-4")
      (cb/apply-invoke missile/electron-missile-abort! :ctx-id "ctx-4"))

    ;; abort applies cooldown too — original funnels release/abort/timeout
    ;; through the same MSG_TERMINATED handler that always sets it.
    (is (= [["p1" :electron-missile 550] ["p1" :electron-missile 550]] @cooldown-calls*))
    (is (= {:ticks 0 :active-balls 0 :active? false}
           (:skill-state @ctx*)))
    (is (= [:electron-missile/fx-end :electron-missile/fx-end]
           (mapv second @local-fx*)))
    (is (= @local-fx* @nearby-fx*))))

(deftest electron-missile-tick-max-hold-terminates-context-test
  (let [{:keys [ctx* get-context update-skill-state-root!]}
        (make-context-mocks {:skill-state {:ticks 80 :active-balls 2 :active? true :overload-floor 200.0}})
        local-fx* (atom [])
        nearby-fx* (atom [])
        terminate-calls* (atom [])
        cooldown-calls* (atom [])]
    (with-redefs [skill-effects/skill-exp (fn [& _] 0.5)
                  skill-config/lerp-int stub-lerp-int
                  skill-config/tunable-int stub-tunable-int
                  skill-config/tunable-double stub-tunable-double
                  skill-effects/enforce-overload-floor! (fn [& _] nil)
                  skill-effects/set-main-cooldown! (fn [player-id skill-id ticks]
                                                     (swap! cooldown-calls* conj [player-id skill-id ticks])
                                                     nil)
                  ctx/get-context get-context
                  ctx-skill/update-skill-state-root! update-skill-state-root!
                  ctx/terminate-context! (fn [& args]
                                           (swap! terminate-calls* conj args)
                                           nil)
                  fx/send! (capture-local-and-nearby-fx! local-fx* nearby-fx*)
                  geom/world-id-of (fn [_] "w")
                  geom/eye-pos (fn [_] {:x 1.0 :y 65.0 :z 2.0})]
      (cb/apply-invoke missile/electron-missile-tick! :player-id "p1" :ctx-id "ctx-5" :player-ref {:id "player-obj"}))

    (is (= 1 (count @terminate-calls*)))
    ;; Exceeding max-hold is also a termination path, and must apply
    ;; cooldown just like a normal release — original funnels it through
    ;; the same MSG_TERMINATED handler.
    (is (= [["p1" :electron-missile 550]] @cooldown-calls*))
    (is (= [:electron-missile/fx-end] (mapv second @local-fx*)))
    (is (= @local-fx* @nearby-fx*))
    (is (= {:ticks 0 :active-balls 0 :active? false}
           (:skill-state @ctx*)))))

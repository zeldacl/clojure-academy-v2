(ns cn.li.ac.content.ability.teleporter.shift-teleport-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.test.skill-callback-test-helpers :as cb]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.effects.geom :as geom]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.context-skill-state :as ctx-skill]
            [cn.li.ac.ability.fx :as fx]
            [cn.li.ac.test.support.fx-mocks :as fx-mocks]
            [cn.li.ac.content.ability.teleporter.shift-teleport :as shift]
            [cn.li.ac.content.ability.teleporter.tp-skill-helper :as helper]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.world-effects :as world-effects]))

(defn- test-context-owner
  [player-uuid]
  {:logical-side :server :server-session-id :test-session :player-uuid (str player-uuid)})

(defn- make-context-mocks [initial-ctx]
  (let [ctx* (atom initial-ctx)]
    {:ctx* ctx*
     :get-context (fn [& _] @ctx*)
     :update-skill-state-root! (fn [_ f & args]
                                 (swap! ctx* update :skill-state
                                        (fn [ss]
                                          (if (and (= f identity) (= 1 (count args)))
                                            (first args)
                                            (apply f (or ss {}) args)))))}))

(defn- shift-tp-platform-redefs [block-hit entities]
  [raycast/available? (constantly true)
   raycast/raycast-blocks* (fn [& _] block-hit)
   world-effects/available? (constantly true)
   world-effects/find-entities-in-aabb* (fn [& _] entities)])

(defn- shift-tp-trace
  [entities & {:keys [eye drop dest place face target-hit?]
               :or {eye {:x 1.0 :y 65.6 :z 3.0}
                    drop [20.5 65.0 21.5]
                    dest [20.5 65.0 21.5]
                    place [20 65 21]
                    face :up
                    target-hit? true}}]
  {:world-id "minecraft:overworld"
   :eye-pos eye
   :drop-x (nth drop 0) :drop-y (nth drop 1) :drop-z (nth drop 2)
   :dest-x (nth dest 0) :dest-y (nth dest 1) :dest-z (nth dest 2)
   :place-x (nth place 0) :place-y (nth place 1) :place-z (nth place 2)
   :face face
   :target-hit? target-hit?
   :range 25.0
   :exp 0.5
   :entities entities})

(defn- shift-tp-ctx-trace-redef [trace]
  (make-context-mocks {:skill-state {:trace trace}}))

(deftest shift-tp-up-place-success-hit-critical-emits-crit-fx-test
  (let [{:keys [get-context]} (shift-tp-ctx-trace-redef
                               (shift-tp-trace [{:uuid "enemy-1" :x 12.0 :y 64.9 :z 13.4 :width 0.6 :height 1.8}
                                                {:uuid "enemy-2" :x 13.0 :y 64.8 :z 14.3 :width 0.6 :height 1.8}]))
        exp-calls* (atom [])
        cooldown-calls* (atom [])
        fx-calls* (atom [])
        damage-calls* (atom [])
        consume-calls* (atom [])]
    (with-redefs [ctx/get-context get-context
                  skill-effects/skill-exp (fn [_ _] 0.5)
                  skill-config/lerp-double (fn [_ field _]
                                    (case field
                                      :targeting.range 25.0
                                      :combat.damage 20.0
                                      :cost.up.cp 260.0
                                      :cost.up.overload 40.0
                                      :cooldown.ticks 18.0
                                      0.0))
                  skill-config/tunable-double (fn [_ field]
                                      (case field
                                        :targeting.eye-height 1.6
                                        :progression.exp-base 0.002
                                        0.0))
                  skill-config/lerp-int (fn [& _] 18)
                  helper/player-position (fn [_] {:x 1.0 :y 64.0 :z 3.0})
                  helper/player-look-vec (fn [_] {:x 0.0 :y 0.0 :z 1.0})
                  geom/world-id-of (fn [_] "minecraft:overworld")
                  entity/player-main-hand-placeable-block? (fn [_] true)
                  entity/player-creative? (fn [_] false)
                  entity/player-drop-main-hand-item-at! (fn [& _] false)
                  entity/player-place-main-hand-block-at-hit! (fn [_ _ _ _ _ _]
                                                                {:placed? true
                                                                 :fallback-drop? false
                                                                 :pos {:x 20 :y 64 :z 21}
                                                                 :face :up})
                  entity/player-consume-main-hand-item! (fn [_ n]
                                                         (swap! consume-calls* conj n)
                                                         true)
                  helper/teleport-to! (fn [& _] true)
                  helper/deal-magic-damage! (fn [_ world-id entity-uuid damage]
                                              (swap! damage-calls* conj [world-id entity-uuid damage])
                                              {:critical? (= entity-uuid "enemy-1")
                                               :crit-level 1
                                               :crit-rate (if (= entity-uuid "enemy-1") 1.6 1.0)
                                               :message-key (when (= entity-uuid "enemy-1") "ability.teleporter.critical_hit")
                                               :message-args (when (= entity-uuid "enemy-1") ["x1.6"])
                                               :damage-after damage
                                               :applied? true})
                  skill-effects/add-skill-exp! (fn [player-id skill-id amount]
                                                 (swap! exp-calls* conj [player-id skill-id amount])
                                                 nil)
                  skill-effects/set-main-cooldown! (fn [player-id skill-id ticks]
                                                     (swap! cooldown-calls* conj [player-id skill-id ticks])
                                                     nil)
                  fx/send! (fn [_ctx-id entry _evt payload]
                             (swap! fx-calls* conj [(:topic entry) payload])
                             nil)]
      (cb/apply-invoke shift/shift-tp-up! :player-id "p1" :ctx-id "ctx-1" :player-ref :player :cost-ok? true))
    (is (= [["minecraft:overworld" "enemy-1" 20.0]
            ["minecraft:overworld" "enemy-2" 20.0]]
           @damage-calls*))
    (is (= [1] @consume-calls*))
    (is (= [["p1" :shift-teleport 0.006]] @exp-calls*))
    (is (= [["p1" :shift-teleport 18]] @cooldown-calls*))
    (is (= :teleporter/fx-crit-hit (first (first @fx-calls*))))
    (is (= {:x 12.0
            :y 64.9
            :z 13.4
            :crit-level 1
            :crit-rate 1.6
            :message-key "ability.teleporter.critical_hit"
            :message-args ["x1.6"]
            :target-uuid "enemy-1"
            :skill-id :shift-teleport}
           (second (first @fx-calls*))))
    (is (= :shift-teleport/fx-perform (first (second @fx-calls*))))))

(deftest shift-tp-up-critical-but-not-applied-skips-crit-fx-test
  (let [{:keys [get-context]} (shift-tp-ctx-trace-redef
                               (shift-tp-trace [{:uuid "enemy-1" :x 12.0 :y 64.9 :z 13.4 :width 0.6 :height 1.8}]
                                               :drop [20.5 65.0 21.5]
                                               :dest [20.5 65.0 21.5]
                                               :place [20 65 21]))
        fx-calls* (atom [])]
    (with-redefs [ctx/get-context get-context
                  skill-effects/skill-exp (fn [_ _] 0.5)
                  skill-config/lerp-double (fn [_ field _]
                                    (case field
                                      :targeting.range 25.0
                                      :combat.damage 20.0
                                      :cost.up.cp 260.0
                                      :cost.up.overload 40.0
                                      :cooldown.ticks 18.0
                                      0.0))
                  skill-config/tunable-double (fn [_ field]
                                      (case field
                                        :targeting.eye-height 1.6
                                        :progression.exp-base 0.002
                                        0.0))
                  skill-config/lerp-int (fn [& _] 18)
                  helper/player-position (fn [_] {:x 1.0 :y 64.0 :z 3.0})
                  helper/player-look-vec (fn [_] {:x 0.0 :y 0.0 :z 1.0})
                  geom/world-id-of (fn [_] "minecraft:overworld")
                  entity/player-main-hand-placeable-block? (fn [_] true)
                  entity/player-creative? (fn [_] false)
                  entity/player-drop-main-hand-item-at! (fn [& _] false)
                  entity/player-place-main-hand-block-at-hit! (fn [& _]
                                                                {:placed? true
                                                                 :fallback-drop? false
                                                                 :pos {:x 20 :y 64 :z 21}
                                                                 :face :up})
                  entity/player-consume-main-hand-item! (fn [& _] true)
                  helper/teleport-to! (fn [& _] true)
                  helper/deal-magic-damage! (fn [& _]
                                              {:critical? true
                                               :crit-level 1
                                               :applied? false})
                  skill-effects/add-skill-exp! (fn [& _] nil)
                  skill-effects/set-main-cooldown! (fn [& _] nil)
                  fx/send! (fn [_ctx-id entry _evt payload]
                             (swap! fx-calls* conj [(:topic entry) payload])
                             nil)]
      (cb/apply-invoke shift/shift-tp-up! :player-id "p1" :ctx-id "ctx-1b" :player-ref :player :cost-ok? true))
    (is (= [[:shift-teleport/fx-perform {:from-x 1.0
                                         :from-y 65.6
                                         :from-z 3.0
                                         :x 20.5
                                         :y 65.0
                                         :z 21.5
                                         :target-count 1
                                         :placed? true
                                         :dropped? false}]]
           @fx-calls*))))

(deftest shift-tp-up-cost-fail-no-side-effects-test
  (let [exp-calls* (atom 0)
        cooldown-calls* (atom 0)
        fx-calls* (atom 0)
        damage-calls* (atom 0)
        teleport-calls* (atom 0)
        place-calls* (atom 0)]
    (with-redefs (into [skill-effects/skill-exp (fn [_ _] 0.5)
                          skill-config/lerp-double (fn [_ _ _] 20.0)
                          helper/player-position (fn [_] {:x 1.0 :y 2.0 :z 3.0})
                          helper/player-look-vec (fn [_] {:x 0.0 :y 0.0 :z 1.0})
                          skill-config/tunable-double (fn [_ _] 1.6)
                          geom/world-id-of (fn [_] "minecraft:overworld")
                          entity/player-main-hand-placeable-block? (fn [_] true)
                          entity/player-drop-main-hand-item-at! (fn [& _] true)
                          entity/player-place-main-hand-block-at-hit! (fn [& _]
                                                                        (swap! place-calls* inc)
                                                                        {:placed? true
                                                                         :fallback-drop? false
                                                                         :pos {:x 4 :y 5 :z 6}
                                                                         :face :up})
                          entity/player-consume-main-hand-item! (fn [& _] true)
                          helper/teleport-to! (fn [& _] (swap! teleport-calls* inc) true)
                          helper/deal-magic-damage! (fn [& _] (swap! damage-calls* inc))
                          skill-effects/add-skill-exp! (fn [& _] (swap! exp-calls* inc))
                          skill-effects/set-main-cooldown! (fn [& _] (swap! cooldown-calls* inc))
                          fx/send! (fn [& _] (swap! fx-calls* inc) nil)]
                 (shift-tp-platform-redefs {:x 4 :y 5 :z 6 :face :up} []))
      (ctx/with-context-owner (test-context-owner "p1")
        (cb/apply-invoke shift/shift-tp-up! :player-id "p1" :ctx-id "ctx-2" :player-ref :player :cost-ok? false)))

    (is (= 0 @place-calls*))
    (is (= 0 @teleport-calls*))
    (is (= 0 @damage-calls*))
    (is (= 0 @exp-calls*))
    (is (= 0 @cooldown-calls*))
    (is (= 0 @fx-calls*))))

(deftest shift-tp-up-place-fail-fallback-drop-test
  (let [{:keys [get-context]} (shift-tp-ctx-trace-redef
                               (shift-tp-trace []
                                               :eye {:x 1.0 :y 3.6 :z 3.0}
                                               :drop [8.5 10.0 10.5]
                                               :dest [8.5 10.0 10.5]
                                               :place [8 10 10]))
        teleport-calls* (atom 0)
        drop-calls* (atom [])
        consume-calls* (atom 0)]
    (with-redefs [ctx/get-context get-context
                  skill-effects/skill-exp (fn [_ _] 0.5)
                  skill-config/lerp-double (fn [_ _ _] 20.0)
                  skill-config/tunable-double (fn [_ field]
                                      (case field
                                        :targeting.eye-height 1.6
                                        :progression.exp-base 0.002
                                        0.0))
                  skill-config/lerp-int (fn [& _] 30)
                  helper/player-position (fn [_] {:x 1.0 :y 2.0 :z 3.0})
                  helper/player-look-vec (fn [_] {:x 0.0 :y 0.0 :z 1.0})
                  geom/world-id-of (fn [_] "minecraft:overworld")
                  entity/player-main-hand-placeable-block? (fn [_] true)
                  entity/player-creative? (fn [_] false)
                  entity/player-drop-main-hand-item-at! (fn [_ n x y z]
                                                         (swap! drop-calls* conj [n x y z])
                                                         true)
                  entity/player-place-main-hand-block-at-hit! (fn [& _]
                                                                {:placed? false
                                                                 :fallback-drop? true
                                                                 :pos {:x 8 :y 9 :z 10}
                                                                 :face :up})
                  entity/player-consume-main-hand-item! (fn [& _]
                                                         (swap! consume-calls* inc)
                                                         true)
                  helper/teleport-to! (fn [& _] (swap! teleport-calls* inc) true)
                  helper/deal-magic-damage! (fn [& _] {:critical? false})
                  skill-effects/add-skill-exp! (fn [& _] nil)
                  skill-effects/set-main-cooldown! (fn [& _] nil)
                  fx/send! (fn [& _] nil)]
      (cb/apply-invoke shift/shift-tp-up! :player-id "p1" :ctx-id "ctx-3" :player-ref :player :cost-ok? true))
    (is (= 1 @teleport-calls*))
    (is (= [[1 8.5 10.0 10.5]] @drop-calls*))
    (is (= 0 @consume-calls*))))

(deftest shift-tp-up-invalid-main-hand-skips-execution-test
  (let [teleport-calls* (atom 0)]
    (with-redefs (into [skill-effects/skill-exp (fn [_ _] 0.5)
                          skill-config/lerp-double (fn [_ _ _] 20.0)
                          helper/player-position (fn [_] {:x 1.0 :y 2.0 :z 3.0})
                          helper/player-look-vec (fn [_] {:x 0.0 :y 0.0 :z 1.0})
                          skill-config/tunable-double (fn [_ _] 1.6)
                          geom/world-id-of (fn [_] "minecraft:overworld")
                          entity/player-main-hand-placeable-block? (fn [_] false)
                          helper/teleport-to! (fn [& _] (swap! teleport-calls* inc) true)
                          fx/send! (fn [& _] nil)]
                 (shift-tp-platform-redefs {:x 8 :y 9 :z 10 :face :up} []))
      (ctx/with-context-owner (test-context-owner "p1")
        (cb/apply-invoke shift/shift-tp-up! :player-id "p1" :ctx-id "ctx-4" :player-ref :player :cost-ok? true)))

    (is (= 0 @teleport-calls*))))

(deftest shift-tp-down-respects-cost-gate-test
  (let [updates* (atom [])]
    (with-redefs [ctx-skill/update-skill-state-root! (fn [ctx-id f & args]
                                                       (swap! updates* conj [ctx-id f args])
                                                       nil)]
      (cb/apply-invoke shift/shift-tp-down! :ctx-id "ctx-cost-fail" :cost-ok? false)
      (cb/apply-invoke shift/shift-tp-down! :ctx-id "ctx-cost-ok" :cost-ok? true))

    (is (= 1 (count @updates*)))
    (is (= "ctx-cost-ok" (ffirst @updates*)))
    (is (= identity (second (first @updates*))))))

(deftest shift-tp-tick-invalid-main-hand-clears-trace-and-skips-fx-test
  (let [updates* (atom [])
        fx-calls* (atom 0)]
    (with-redefs [entity/player-main-hand-placeable-block? (fn [_] false)
                  ctx-skill/update-skill-state-root! (fn [ctx-id f & args]
                                                       (swap! updates* conj [ctx-id f args])
                                                       nil)
                  fx/send! (fn [& _] (swap! fx-calls* inc) nil)]
      (cb/apply-invoke shift/shift-tp-tick! :player-id "p1" :player-ref :player :ctx-id "ctx-tick" :hold-ticks 9))

    (is (= 1 (count @updates*)))
    (is (= 0 @fx-calls*))
    (let [[_ _ args] (first @updates*)]
      (is (= {:hold-ticks 9 :hand-valid? false :trace nil} (first args))))))

(deftest shift-tp-up-creative-mode-skips-consume-test
  (let [{:keys [get-context]} (shift-tp-ctx-trace-redef
                               (shift-tp-trace []
                                               :drop [8.5 65.0 10.5]
                                               :dest [8.5 65.0 10.5]
                                               :place [8 65 10]))
        consume-calls* (atom 0)
        teleport-calls* (atom 0)]
    (with-redefs [ctx/get-context get-context
                  skill-effects/skill-exp (fn [_ _] 0.5)
                  skill-config/lerp-double (fn [_ field _]
                                    (case field
                                      :targeting.range 25.0
                                      :combat.damage 10.0
                                      :cost.up.cp 260.0
                                      :cost.up.overload 40.0
                                      :cooldown.ticks 20.0
                                      0.0))
                  skill-config/tunable-double (fn [_ field]
                                      (case field
                                        :targeting.eye-height 1.6
                                        :progression.exp-base 0.002
                                        0.0))
                  skill-config/lerp-int (fn [& _] 20)
                  helper/player-position (fn [_] {:x 1.0 :y 64.0 :z 3.0})
                  helper/player-look-vec (fn [_] {:x 0.0 :y 0.0 :z 1.0})
                  geom/world-id-of (fn [_] "minecraft:overworld")
                  entity/player-main-hand-placeable-block? (fn [_] true)
                  entity/player-creative? (fn [_] true)
                  entity/player-drop-main-hand-item-at! (fn [& _] false)
                  entity/player-place-main-hand-block-at-hit! (fn [& _]
                                                                {:placed? true
                                                                 :fallback-drop? false
                                                                 :pos {:x 8 :y 65 :z 10}
                                                                 :face :up})
                  entity/player-consume-main-hand-item! (fn [& _]
                                                         (swap! consume-calls* inc)
                                                         true)
                  helper/teleport-to! (fn [& _] (swap! teleport-calls* inc) true)
                  helper/deal-magic-damage! (fn [& _] {:critical? false})
                  skill-effects/add-skill-exp! (fn [& _] nil)
                  skill-effects/set-main-cooldown! (fn [& _] nil)
                  fx/send! (fn [& _] nil)]
      (cb/apply-invoke shift/shift-tp-up! :player-id "p1" :ctx-id "ctx-creative" :player-ref :player :cost-ok? true))
    (is (= 1 @teleport-calls*))
    (is (= 0 @consume-calls*))))

(ns cn.li.ac.content.ability.teleporter.flashing-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.test.skill-callback-test-helpers :as cb]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.context-skill-state :as ctx-skill]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.fx :as fx]
            [cn.li.ac.test.support.skill-context :as skill-ctx]
            [cn.li.ac.achievement.dispatcher :as ach-dispatcher]
            [cn.li.ac.content.ability.teleporter.flashing :as flashing]
            [cn.li.ac.content.ability.teleporter.tp-skill-helper :as helper]
            [cn.li.ac.ability.effects.geom :as geom]))

(defn- with-flashing-env [f]
  (skill-ctx/with-server-skill-context f))

(defn- make-context-mocks [initial]
  (let [listeners* (atom {})
        base (skill-ctx/content-ctx-mocks initial)]
  (assoc base
         :listeners* listeners*
         :ctx-on! (fn [_ channel handler]
                    (swap! listeners* assoc channel handler)
                    nil))))

(deftest flashing-activate-registers-movement-listeners-test
  (let [{:keys [ctx* listeners* get-context update-skill-state-root! assoc-skill-state!
                clear-skill-state! ctx-on!]}
        (make-context-mocks {:player-uuid "p1" :skill-id :flashing :skill-state {}})]
    (with-flashing-env
      #(with-redefs [ctx/get-context get-context
                    ctx/ctx-on! ctx-on!
                    ctx-skill/update-skill-state-root! update-skill-state-root!
                    ctx-skill/assoc-skill-state! assoc-skill-state!
                    ctx-skill/clear-skill-state! clear-skill-state!
                    skill-effects/skill-exp (fn [_ _] 0.5)
                    skill-config/lerp-int (fn [_ field-id _]
                                            (case field-id
                                              :timing.max-active-ticks 90
                                              :cooldown.deactivate-ticks 600
                                              :timing.post-blink-fall-protect-ticks 40
                                              0))
                    skill-effects/player-path (fn [_ _ _] 42.0)]
         (cb/apply-invoke flashing/flashing-activate! :ctx-id "ctx-1" :player-id "p1" :cost-ok? true)))
    (is (true? (get-in @ctx* [:skill-state :active?])))
    (is (true? (get-in @ctx* [:skill-state :listeners-installed?])))
    (is (= 42.0 (get-in @ctx* [:skill-state :overload-floor])))
    (is (contains? @listeners* :flashing/move-down))
    (is (contains? @listeners* :flashing/move-tick))
    (is (contains? @listeners* :flashing/move-up))))

(deftest flashing-movement-up-performs-teleport-and-effects-test
  (let [{:keys [listeners* get-context update-skill-state-root! assoc-skill-state!
                clear-skill-state! ctx-on!]}
        (make-context-mocks {:player-uuid "p1" :skill-id :flashing :skill-state {}})
        teleports* (atom [])
        resources* (atom [])
        fx* (atom [])
        exp* (atom [])
        ach* (atom [])
        reset-fall* (atom [])]
    (with-flashing-env
      #(with-redefs [ctx/get-context get-context
                    ctx/ctx-on! ctx-on!
                    ctx/terminate-context! (fn [& _] nil)
                    ctx-skill/update-skill-state-root! update-skill-state-root!
                    ctx-skill/assoc-skill-state! assoc-skill-state!
                    ctx-skill/clear-skill-state! clear-skill-state!
                    fx/send! (fn [_ctx-id entry _evt payload]
                               (swap! fx* conj [(:topic entry) payload])
                               nil)
                    skill-effects/skill-exp (fn [_ _] 0.5)
                    skill-config/lerp-double (fn [_ field-id _]
                                             (case field-id
                                               :movement.blink-distance 4.0
                                               :cost.blink.cp 12.0
                                               :cost.blink.overload 6.0
                                               :cost.down.cp 70.0
                                               :cost.down.overload 120.0
                                               0.0))
                    skill-config/lerp-int (fn [_ field-id _]
                                            (case field-id
                                              :timing.max-active-ticks 80
                                              :timing.post-blink-fall-protect-ticks 40
                                              :cooldown.deactivate-ticks 500
                                              0))
                    skill-config/tunable-double (fn [_ _] 0.001)
                    skill-effects/current-cp (fn [_] 100.0)
                    skill-effects/player-path (fn [_ _ _] 33.0)
                    skill-effects/enforce-overload-floor! (fn [& _] true)
                    helper/player-look-vec (fn [_] {:x 0.0 :y 0.0 :z 1.0})
                    helper/player-position (fn [_] {:x 10.0 :y 64.0 :z 10.0})
                    helper/raycast-combined (fn [& _] nil)
                    geom/world-id-of (fn [_] "minecraft:overworld")
                    helper/teleport-to! (fn [player-id world-id x y z]
                                        (swap! teleports* conj [player-id world-id x y z])
                                        true)
                    helper/reset-fall-damage! (fn [player-id]
                                              (swap! reset-fall* conj player-id)
                                              true)
                    skill-effects/perform-resource! (fn [player-id overload cp]
                                                      (swap! resources* conj [player-id overload cp])
                                                      {:success? true})
                    skill-effects/add-skill-exp! (fn [player-id skill-id amount]
                                                   (swap! exp* conj [player-id skill-id amount]))
                    ach-dispatcher/trigger-custom-event! (fn [player-id event-id]
                                                           (swap! ach* conj [player-id event-id]))]
         (cb/apply-invoke flashing/flashing-activate! :ctx-id "ctx-1" :player-id "p1" :cost-ok? true)
         ((get @listeners* :flashing/move-down) {:key :forward})
         ((get @listeners* :flashing/move-up) {:key :forward})))
    (is (= [["p1" "minecraft:overworld" 10.0 64.0 14.0]] @teleports*))
    (is (= [["p1" 6.0 12.0]] @resources*))
    (is (= [["p1" :flashing 0.001]] @exp*))
    (is (= [["p1" "teleporter.flashing"]] @ach*))
    (is (seq @reset-fall*))
    (is (some #(= :flashing/fx-perform (first %)) @fx*))))

(deftest flashing-timeout-terminates-on-movement-event-test
  (let [{:keys [ctx* listeners* get-context update-skill-state-root! assoc-skill-state!
                clear-skill-state! ctx-on!]}
        (make-context-mocks {:player-uuid "p1" :skill-id :flashing :skill-state {}})
        terminated* (atom [])]
    (with-flashing-env
      #(with-redefs [ctx/get-context get-context
                    ctx/ctx-on! ctx-on!
                    ctx/terminate-context! (fn [ctx-id _]
                                             (swap! terminated* conj ctx-id)
                                             nil)
                    ctx-skill/update-skill-state-root! update-skill-state-root!
                    ctx-skill/assoc-skill-state! assoc-skill-state!
                    ctx-skill/clear-skill-state! clear-skill-state!
                    skill-effects/skill-exp (fn [_ _] 0.5)
                    skill-config/lerp-int (fn [_ field-id _]
                                            (case field-id
                                              :timing.max-active-ticks 80
                                              :timing.post-blink-fall-protect-ticks 40
                                              :cooldown.deactivate-ticks 500
                                              0))
                    skill-effects/player-path (fn [_ _ _] 10.0)
                    skill-effects/enforce-overload-floor! (fn [& _] true)]
         (cb/apply-invoke flashing/flashing-activate! :ctx-id "ctx-1" :player-id "p1" :cost-ok? true)
         (swap! ctx* assoc-in [:skill-state :expires-at-ms] 1)
         ((get @listeners* :flashing/move-down) {:key :forward})))
    (is (= ["ctx-1"] @terminated*))))

(deftest flashing-block-hit-resolution-applies-side-and-head-correction-test
  (let [{:keys [listeners* get-context update-skill-state-root! assoc-skill-state!
                clear-skill-state! ctx-on!]}
        (make-context-mocks {:player-uuid "p1" :skill-id :flashing :skill-state {}})
        fx* (atom [])]
    (with-flashing-env
      #(with-redefs [ctx/get-context get-context
                    ctx/ctx-on! ctx-on!
                    ctx/terminate-context! (fn [& _] nil)
                    ctx-skill/update-skill-state-root! update-skill-state-root!
                    ctx-skill/assoc-skill-state! assoc-skill-state!
                    ctx-skill/clear-skill-state! clear-skill-state!
                    fx/send! (fn [_ctx-id entry _evt payload]
                               (swap! fx* conj [(:topic entry) payload])
                               nil)
                    skill-effects/skill-exp (fn [_ _] 0.5)
                    skill-config/lerp-double (fn [_ field-id _]
                                             (case field-id
                                               :movement.blink-distance 12.0
                                               :cost.blink.cp 10.0
                                               :cost.blink.overload 0.0
                                               :cost.down.cp 70.0
                                               :cost.down.overload 120.0
                                               0.0))
                    skill-config/lerp-int (fn [_ field-id _]
                                            (case field-id
                                              :timing.max-active-ticks 80
                                              :timing.post-blink-fall-protect-ticks 40
                                              :cooldown.deactivate-ticks 500
                                              0))
                    skill-effects/player-path (fn [_ _ _] 20.0)
                    skill-effects/enforce-overload-floor! (fn [& _] true)
                    helper/player-look-vec (fn [_] {:x 0.0 :y 0.0 :z 1.0})
                    helper/player-position (fn [_] {:world-id "minecraft:overworld" :x 10.0 :y 64.0 :z 10.0})
                    helper/raycast-combined (fn [& _]
                                              {:hit-type :block
                                               :hit-x 10.0
                                               :hit-y 65.0
                                               :hit-z 10.0
                                               :x 10.0
                                               :y 65.0
                                               :z 10.0
                                               :face :north})
                    helper/raycast-blocks (fn [& _] {:x 10 :y 66 :z 9})]
         (cb/apply-invoke flashing/flashing-activate! :ctx-id "ctx-1" :player-id "p1" :cost-ok? true)
         ((get @listeners* :flashing/move-down) {:key :forward})))
    (let [[topic payload] (last @fx*)]
      (is (= 10.0 (:to-x payload)))
      (is (< (Math/abs (- 65.45 (double (:to-y payload)))) 1.0e-6))
      (is (< (Math/abs (- 9.4 (double (:to-z payload)))) 1.0e-6)))))

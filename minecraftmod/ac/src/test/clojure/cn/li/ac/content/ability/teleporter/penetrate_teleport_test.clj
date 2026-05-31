(ns cn.li.ac.content.ability.teleporter.penetrate-teleport-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.achievement.dispatcher :as ach-dispatcher]
            [cn.li.ac.content.ability.teleporter.penetrate-teleport :as pt]
            [cn.li.ac.content.ability.teleporter.tp-skill-helper :as helper]
            [cn.li.ac.ability.effects.geom :as geom]))

(defn- make-context-mocks [initial]
  (let [ctx* (atom initial)
        listeners* (atom {})]
    {:ctx* ctx*
     :listeners* listeners*
     :get-context (fn [_] @ctx*)
     :update-context! (fn [_ f & args]
                        (swap! ctx* #(when % (apply f % args))))
     :ctx-on! (fn [_ channel handler]
                (swap! listeners* assoc channel handler)
                nil)}))

(deftest penetrate-down-registers-distance-listener-and-updates-state-test
  (let [{:keys [ctx* listeners* get-context update-context! ctx-on!]}
        (make-context-mocks {:skill-state {}})]
    (with-redefs [ctx/get-context get-context
                  ctx/update-context! update-context!
                  ctx/ctx-on! ctx-on!
                  helper/skill-exp (fn [_ _] 0.2)
                  helper/cfg-lerp (fn [_ field _]
                                    (case field
                                      :targeting.max-distance 20.0
                                      0.0))
                  pt/resolve-preview (fn [_player-id desired]
                                       {:distance desired
                                        :cp-per-block 10.0
                                        :available? true
                                        :dest {:x desired :y 64.0 :z 0.0}})]
      (pt/penetrate-tp-down! {:player-id "p1" :ctx-id "ctx-1" :cost-ok? true})
      ((get @listeners* :penetrate-tp/set-distance) {:delta -50.0}))

    (is (= 0 (get-in @ctx* [:skill-state :hold-ticks])))
    (is (= 0.5 (get-in @ctx* [:skill-state :desired-distance])))
    (is (= true (get-in @ctx* [:skill-state :preview :available?])))))

(deftest penetrate-tick-refreshes-preview-and-resets-up-resolve-test
  (let [{:keys [ctx* get-context update-context!]}
        (make-context-mocks {:skill-state {:desired-distance 6.0
                                           :up-resolve {:distance 3.0}}})]
    (with-redefs [ctx/get-context get-context
                  ctx/update-context! update-context!
                  pt/resolve-preview (fn [_player-id desired]
                                       {:distance desired
                                        :cp-per-block 9.0
                                        :available? true
                                        :dest {:x 1.0 :y 2.0 :z 3.0}})]
      (pt/penetrate-tp-tick! {:player-id "p1" :ctx-id "ctx-2" :hold-ticks 7}))

    (is (= 7 (get-in @ctx* [:skill-state :hold-ticks])))
    (is (= 6.0 (get-in @ctx* [:skill-state :preview :distance])))
    (is (nil? (get-in @ctx* [:skill-state :up-resolve])))))

(deftest penetrate-up-success-and-cost-fail-side-effects-test
  (let [exp-calls* (atom [])
        cooldown-calls* (atom [])
        fx-calls* (atom [])
        ach-calls* (atom [])
        teleport-calls* (atom [])]
    (with-redefs [pt/ensure-up-resolve! (fn [_ctx-id _player-id]
                                          {:exp 0.3
                                           :distance 8.0
                                           :available? true
                                           :dest {:x 10.0 :y 64.0 :z 12.0}})
                  geom/world-id-of (fn [_] "minecraft:overworld")
                  helper/teleport-to! (fn [player-id world-id x y z]
                                        (swap! teleport-calls* conj [player-id world-id x y z])
                                        true)
                  pt/cooldown-ticks (fn [_] 40)
                  pt/exp-per-distance (fn [] 0.001)
                  skill-effects/add-skill-exp! (fn [player-id skill-id amount]
                                                 (swap! exp-calls* conj [player-id skill-id amount]))
                  skill-effects/set-main-cooldown! (fn [player-id skill-id ticks]
                                                     (swap! cooldown-calls* conj [player-id skill-id ticks]))
                  ctx/ctx-send-to-client! (fn [ctx-id channel payload]
                                            (swap! fx-calls* conj [ctx-id channel payload]))
                  ach-dispatcher/trigger-custom-event! (fn [player-id event-id]
                                                         (swap! ach-calls* conj [player-id event-id]))]
      (pt/penetrate-tp-up! {:player-id "p1" :ctx-id "ctx-ok" :cost-ok? true})
      (pt/penetrate-tp-up! {:player-id "p2" :ctx-id "ctx-fail" :cost-ok? false}))

    (is (= [["p1" "minecraft:overworld" 10.0 64.0 12.0]] @teleport-calls*))
    (is (= [["p1" :penetrate-teleport 0.008]] @exp-calls*))
    (is (= [["p1" :penetrate-teleport 40]] @cooldown-calls*))
    (is (= [["ctx-ok" :penetrate-tp/fx-perform {:x 10.0 :y 64.0 :z 12.0}]] @fx-calls*))
    (is (= [["p1" "teleporter.ignore_barrier"]] @ach-calls*))))


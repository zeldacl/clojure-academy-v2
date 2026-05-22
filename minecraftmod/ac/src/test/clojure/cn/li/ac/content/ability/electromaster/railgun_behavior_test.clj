(ns cn.li.ac.content.ability.electromaster.railgun-behavior-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.item-actions :as item-actions]
            [cn.li.ac.ability.server.effect.beam]
            [cn.li.ac.ability.server.effect.core :as effect]
            [cn.li.ac.ability.server.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.service.dispatcher :as ctx]
            [cn.li.ac.ability.service.player-state :as player-state]
            [cn.li.ac.content.ability.electromaster.railgun :as railgun]
            [cn.li.mcmod.platform.block-manipulation :as block-manip]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.util.log :as log]))

(defn- reset-state! [f]
    (let [player-states-val @player-state/player-states
      context-registry-val @@#'cn.li.ac.ability.service.dispatcher/context-registry
      item-action-registry-val @@#'cn.li.ac.ability.item-actions/item-action-registry
      action-handlers-val @@#'cn.li.ac.ability.item-actions/action-handlers
      item-entity-spawns-val @@#'cn.li.ac.ability.item-actions/item-entity-spawns]
    (try
      (reset! player-state/player-states {})
      (reset! @#'cn.li.ac.ability.service.dispatcher/context-registry {})
      (reset! @#'cn.li.ac.ability.item-actions/item-action-registry {})
      (reset! @#'cn.li.ac.ability.item-actions/action-handlers {})
      (reset! @#'cn.li.ac.ability.item-actions/item-entity-spawns {})
      (f)
      (finally
        (reset! player-state/player-states player-states-val)
        (reset! @#'cn.li.ac.ability.service.dispatcher/context-registry context-registry-val)
        (reset! @#'cn.li.ac.ability.item-actions/item-action-registry item-action-registry-val)
        (reset! @#'cn.li.ac.ability.item-actions/action-handlers action-handlers-val)
        (reset! @#'cn.li.ac.ability.item-actions/item-entity-spawns item-entity-spawns-val)))))

(use-fixtures :once (fn [f] (effect/init-default-ops!) (f)))
(use-fixtures :each reset-state!)

(deftest beam-uses-trace-origin-but-keeps-visual-origin-test
  (let [calls (atom [])]
    (with-redefs [world-effects/*world-effects* :world
                  block-manip/*block-manipulation* nil
                  entity-damage/*entity-damage* nil
                  raycast/*raycast* nil
                  ctx/ctx-send-to-client! (fn [_ctx-id ch payload]
                                            (swap! calls conj [:fx ch payload]))
                  world-effects/find-entities-in-radius
                  (fn [& args]
                    (swap! calls conj [:search args])
                    [{:uuid "e-1" :x 3.0 :y 2.0 :z 3.0}])
                  entity-damage/apply-direct-damage! (fn [& _]
                                                       (swap! calls conj [:damage]))]
      (let [evt {:ctx-id "ctx-1"
                 :player-id "p1"
                 :world-id "w1"
                 :eye-pos {:x 10.0 :y 20.0 :z 30.0}
                 :look-dir {:dx 0.0 :dy 0.0 :dz 1.0}}
            out (effect/run-op! evt [:beam {:trace-pos {:x 1.0 :y 2.0 :z 3.0}
                                            :radius 1.0
                                            :query-radius 10.0
                                            :step 0.9
                                            :max-distance 8.0
                                            :visual-distance 5.0
                                            :damage 12.0
                                            :block-energy 0.0
                                            :break-blocks? false
                                            :fx-topic :railgun/fx-shot}])]
        (is (true? (get-in out [:beam-result :performed?])))
        (is (= ["w1" 1.0 2.0 3.0 10.0] (-> @calls first second rest vec)))
        (is (= [:fx :railgun/fx-shot {:mode :perform
                                      :start {:x 10.0 :y 20.0 :z 30.0}
                                      :end {:x 10.0 :y 20.0 :z 35.0}
                                      :hit-distance 5.0}]
               (second @calls)))
        (is (not-any? #(= :damage (first %)) @calls))))))

(deftest coin-throw-aborts-item-charge-and-opens-window-test
  (let [updates (atom [])]
    (player-state/set-player-state! "p1" (player-state/fresh-state))
    (ctx/register-context! {:id "ctx-1"
                            :player-uuid "p1"
                            :skill-id :railgun
                            :status ctx/STATUS-ALIVE
                            :skill-state {:mode :item-charge :charge-ticks 3 :fired false}})
    (with-redefs [skill-effects/assoc-player-path! (fn [player-id path value]
                                                      (swap! updates conj [:assoc player-id path value])
                                                      true)
                  log/debug (fn [& _])]
      (is (true? (railgun/register-coin-throw! "p1" {:timestamp-ms 12345})))
      (is (= 1 (count @updates)))
      (is (= [:assoc "p1" [:runtime :railgun :coin-window]
              {:start-ms 12345 :window-ms 1000 :source :coin-item}]
             (first @updates)))
      (is (= :item-charge-cancelled (get-in (ctx/get-context "ctx-1") [:skill-state :mode]))))))
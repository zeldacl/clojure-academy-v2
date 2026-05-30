(ns cn.li.ac.content.ability.electromaster.railgun-behavior-test
  (:require 
            [cn.li.ac.ability.service.player-state-core :as ps-core]
[clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.item-actions :as item-actions]
            [cn.li.ac.ability.server.effect.beam]
            [cn.li.ac.ability.server.effect.core :as effect]
            [cn.li.ac.ability.service.dispatcher :as ctx]            [cn.li.ac.test.support.player-state :as ps-fix]
            [cn.li.ac.content.ability.electromaster.railgun :as railgun]
            [cn.li.mcmod.platform.block-manipulation :as block-manip]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.util.log :as log]))

(defn- reset-state! [f]
  (ps-fix/with-test-player-state-owner
    (fn []
      (let [player-states-val (ps-core/snapshot-player-states)
            context-registry-val (ctx/snapshot-context-registry)
            item-actions-snapshot (item-actions/item-action-registries-snapshot)]
        (try
          (ps-core/reset-player-states-for-test!)
          (ctx/reset-contexts-for-test!)
          (item-actions/reset-item-action-registries-for-test!)
          (f)
          (finally
            (ps-core/reset-player-states-for-test! player-states-val)
            (ctx/reset-contexts-for-test! context-registry-val)
            (item-actions/reset-item-action-registries-for-test! item-actions-snapshot)))))))

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
  (ps-core/set-player-state! "p1" (ps-core/fresh-state))
  (ctx/register-context! {:id "ctx-1"
                          :player-uuid "p1"
                          :skill-id :railgun
                          :logical-side :server
                          :session-id :test-session
                          :status ctx/STATUS-ALIVE
                          :skill-state {:mode :item-charge :charge-ticks 3 :fired false}})
  (with-redefs [log/debug (fn [& _])]
    (binding [ctx/*context-owner* {:logical-side :server :session-id :test-session}]
      (is (true? (railgun/register-coin-throw! "p1" {:timestamp-ms 12345})))
      (is (= :item-charge-cancelled (get-in (ctx/get-context "ctx-1") [:skill-state :mode]))))))

(deftest coin-progress-threshold-status-test
  (let [below (#'railgun/qte-status 0.59)
        active (#'railgun/qte-status 0.6)
    edge (#'railgun/qte-status 0.7)
    perform (#'railgun/qte-status 0.71)]
    (is (true? (:has-window? below)))
    (is (false? (:active? below)))
    (is (false? (:perform? below)))

    (is (true? (:active? active)))
    (is (false? (:perform? active)))

  (is (true? (:active? edge)))
  (is (false? (:perform? edge)))

    (is (true? (:active? perform)))
    (is (true? (:perform? perform)))))

(deftest read-coin-qte-status-skips-already-judged-coin-test
  (ps-core/set-player-state! "p1" (ps-core/fresh-state))
  (railgun/register-coin-throw! "p1" {:timestamp-ms 42})
  (ps-core/update-player-state! "p1" assoc-in [:runtime :railgun :coin-judged-uuid] "coin-1")
  (with-redefs [world-effects/*world-effects* :mock-world
                world-effects/find-entities-in-radius (fn [& _]
                                                        [{:type "entity_coin_throwing" :uuid "coin-1" :motion-progress 0.95}])
                railgun/coin-candidates (fn [_]
                                         [{:type "entity_coin_throwing" :uuid "coin-1" :motion-progress 0.95}])]
    (let [status (#'railgun/read-coin-qte-status "p1")]
      (is (false? (:has-window? status)))
      (is (false? (:perform? status))))))


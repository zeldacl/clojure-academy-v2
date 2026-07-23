(ns cn.li.ac.ability.server.service.delayed-projectiles-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cn.li.ac.ability.effects.beam :as beam]
            [cn.li.ac.ability.effects.geom :as geom]
            [cn.li.ac.ability.service.player-runtime-commands :as prt-cmd]
            [cn.li.ac.ability.service.runtime-store :as store]
            [cn.li.ac.test.support.player-state :as ps-fix]
            [cn.li.ac.ability.service.delayed-projectiles :as dp]
            [cn.li.ac.content.ability.meltdowner.damage-helper :as md-damage]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.service.context-manager :as ctx-mgr]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]))

(defn- with-fresh-delayed-projectile-runtime [f]
  (ps-fix/with-test-player-state-owner
    (fn []
      (store/reset-store!)
      (ps-fix/seed-player-state! "p1" {})
      (ps-fix/seed-player-state! "p2" {})
      (try
        (f)
        (finally
          (dp/reset-pending-tasks-for-test!)
          (store/reset-store!))))))

(use-fixtures :each with-fresh-delayed-projectile-runtime)

(deftest mdball-near-expire-delay-test
  (is (= 15 (dp/mdball-near-expire-delay)))
  (is (= 1 (dp/mdball-near-expire-delay 1)))
  (is (= 5 (dp/mdball-near-expire-delay 10))))

(deftest electron-bomb-settlement-hit-path-test
  (testing "eye/look-dir are re-fetched fresh at settle time, not read from the scheduled task"
    (let [calls (atom [])]
      (with-redefs [raycast/available? (constantly true)
                    entity-damage/available? (constantly true)
                    geom/world-id-of (fn [_] "w")
                    geom/eye-pos (fn [_] {:x 1.0 :y 64.0 :z 2.0})
                    raycast/player-look-vector (fn [_] {:x 0.0 :y 0.0 :z 1.0})
                    raycast/raycast-entities (fn [& _]
                                               {:uuid "target-1"
                                                :x 4.0
                                                :y 65.0
                                                :z 6.0
                                                :distance 9.0})
                    entity-damage/apply-direct-damage! (fn [& args]
                                                        (swap! calls conj [:damage (vec args)])
                                                        true)
                    md-damage/mark-target! (fn [& args]
                                             (swap! calls conj [:mark (vec args)])
                                             true)
                    skill-effects/add-skill-exp! (fn [& args]
                                                   (swap! calls conj [:exp (vec args)])
                                                   true)
                    ctx-mgr/push-channel-to-player! (fn [& args]
                                                     (swap! calls conj [:fx (vec args)])
                                                     true)
                    ctx-mgr/push-channel-to-nearby-players! (fn [& args]
                                                             (swap! calls conj [:fx-nearby (vec args)])
                                                             true)]
        (dp/schedule-electron-bomb-beam!
         {:player-id "p1"
          :ctx-id "ctx-1"
          :damage 12.5
          :delay-ticks 1})
        (dp/tick-player! "p1")
        ;; Exp is no longer granted here — original grants it unconditionally
        ;; at cast time, so the skill's perform! now owns that call.
        (is (= [[:damage ["w" "target-1" 12.5 :magic]]
            [:mark ["p1" "target-1" {:ctx-id "ctx-1"
                    :target-pos {:x 4.0 :y 65.0 :z 6.0}}]]
                [:fx ["p1"
                      "ctx-1"
                      :electron-bomb/fx-beam
                      {:mode :perform
                       :start {:x 1.0 :y 64.0 :z 2.0}
                       :end {:x 1.0 :y 64.0 :z 17.0}
                       :hit-distance 15.0
                       :performed? true
                     :target-uuid "target-1"}]]
                  [:fx-nearby ["ctx-1"
                       :electron-bomb/fx-beam
                       {:mode :perform
                        :start {:x 1.0 :y 64.0 :z 2.0}
                        :end {:x 1.0 :y 64.0 :z 17.0}
                        :hit-distance 15.0
                        :performed? true
                        :target-uuid "target-1"}]]]
               @calls))
        (is (empty? (dp/pending-tasks-snapshot "p1")))))))

(deftest electron-bomb-settlement-without-look-vector-is-noop-test
  (let [calls (atom [])]
    (with-redefs [raycast/available? (constantly true)
                  geom/world-id-of (fn [_] "w")
                  geom/eye-pos (fn [_] {:x 1.0 :y 64.0 :z 2.0})
                  raycast/player-look-vector (fn [_] nil)
                  raycast/raycast-entities (fn [& _]
                                             (swap! calls conj :raycast)
                                             nil)
                  entity-damage/apply-direct-damage! (fn [& _]
                                                      (swap! calls conj :damage)
                                                      true)
                  md-damage/mark-target! (fn [& _]
                                           (swap! calls conj :mark)
                                           true)
                  skill-effects/add-skill-exp! (fn [& _]
                                                 (swap! calls conj :exp)
                                                 true)
                  ctx-mgr/push-channel-to-player! (fn [& _]
                                                   (swap! calls conj :fx)
                                                   true)]
      (dp/schedule-electron-bomb-beam!
       {:player-id "p1"
        :ctx-id "ctx-1"
        :damage 12.5
        :delay-ticks 1})
      (dp/tick-player! "p1")
      (is (empty? @calls))
      (is (empty? (dp/pending-tasks-snapshot "p1"))))))

(deftest scatter-bomb-settlement-order-and-cleanup-test
  (let [calls (atom [])]
    (with-redefs [beam/execute-beam! (fn [_ _]
                       {:beam-result {:visual-distance 23.0
                              :hit-uuids ["target-1"]}})
                  md-damage/mark-target! (fn [player-id target-id fx-context]
                                           (swap! calls conj [:mark player-id target-id fx-context])
                                           true)
                  ctx-mgr/push-channel-to-player! (fn [player-id ctx-id ch payload]
                                                   (swap! calls conj [:fx player-id ctx-id ch payload])
                                                   true)
                  ctx-mgr/push-channel-to-nearby-players! (fn [ctx-id ch payload]
                                                           (swap! calls conj [:fx-nearby ctx-id ch payload])
                                                           true)]
      (dp/schedule-scatter-bomb-beam!
       {:player-id "p1"
        :ctx-id "ctx-1"
        :world-id "w"
        :eye {:x 1.0 :y 64.0 :z 2.0}
        :look-dir {:x 0.0 :y 0.0 :z 1.0}
        :damage 7.0
        :beam {:radius 0.3 :query-radius 20.0 :step 0.8 :max-distance 25.0 :visual-distance 23.0}
        :delay-ticks 1})
      (dp/schedule-scatter-bomb-beam!
       {:player-id "p1"
        :ctx-id "ctx-1"
        :world-id "w"
        :eye {:x 1.0 :y 64.0 :z 2.0}
        :look-dir {:x 0.0 :y 0.0 :z 1.0}
        :damage 7.0
        :beam {:radius 0.3 :query-radius 20.0 :step 0.8 :max-distance 25.0 :visual-distance 23.0}
        :delay-ticks 2})

      (dp/tick-player! "p1")
  (is (= 3 (count @calls)))
      (is (= [:mark "p1" "target-1" {:ctx-id "ctx-1"}] (first @calls)))
      (is (= 1 (count (dp/pending-tasks-snapshot "p1"))))

      (dp/tick-player! "p1")
  (is (= 6 (count @calls)))
      (is (empty? (dp/pending-tasks-snapshot "p1"))))))

(deftest pending-tasks-are-player-keyed-and-clearable-test
  (dp/schedule-task! "p1" 2 {:kind :unknown :payload 1})
  (dp/schedule-task! "p2" 2 {:kind :unknown :payload 2})
  (is (= 1 (count (dp/pending-tasks-snapshot "p1"))))
  (is (= 1 (count (dp/pending-tasks-snapshot "p2"))))
  (dp/clear-player-tasks! "p1")
  (is (empty? (dp/pending-tasks-snapshot "p1")))
  (is (= 1 (count (dp/pending-tasks-snapshot "p2")))))

(deftest delayed-projectile-tasks-are-per-player-state-test
  (dp/schedule-task! "p1" 2 {:kind :unknown :payload :a})
  (dp/schedule-task! "p2" 2 {:kind :unknown :payload :b})
  (is (= [{:kind :unknown :payload :a :ticks-left 2}]
         (dp/pending-tasks-snapshot "p1")))
  (is (= [{:kind :unknown :payload :b :ticks-left 2}]
         (dp/pending-tasks-snapshot "p2")))
  (dp/clear-player-tasks! "p1")
  (is (empty? (dp/pending-tasks-snapshot "p1")))
  (is (= 1 (count (dp/pending-tasks-snapshot "p2")))))

(deftest scatter-bomb-settlement-uses-task-look-dir-test
  (let [run-op-inputs* (atom [])]
    (with-redefs [beam/execute-beam! (fn [ctx _spec]
                                       (swap! run-op-inputs* conj {:look-dir (:look-dir ctx)
                                                                   :eye-pos (:eye-pos ctx)})
                                       {:beam-result {:visual-distance 23.0
                                                      :hit-uuids []}})
                  ctx-mgr/push-channel-to-player! (fn [& _] true)
                  ctx-mgr/push-channel-to-nearby-players! (fn [& _] true)
                  md-damage/mark-target! (fn [& _] true)]
      (dp/schedule-scatter-bomb-beam!
       {:player-id "p1"
        :ctx-id "ctx-1"
        :world-id "w"
        :eye {:x 1.0 :y 64.0 :z 2.0}
        :look-dir {:x 1.0 :y 0.0 :z 0.0}
        :damage 7.0
        :beam {:radius 0.3 :query-radius 20.0 :step 0.8 :max-distance 25.0 :visual-distance 23.0}
        :delay-ticks 1})
      (dp/schedule-scatter-bomb-beam!
       {:player-id "p1"
        :ctx-id "ctx-1"
        :world-id "w"
        :eye {:x 1.0 :y 64.0 :z 2.0}
        :look-dir {:x 0.0 :y 1.0 :z 0.0}
        :damage 7.0
        :beam {:radius 0.3 :query-radius 20.0 :step 0.8 :max-distance 25.0 :visual-distance 23.0}
        :delay-ticks 1})
      (dp/tick-player! "p1")
      (is (= [{:look-dir {:x 1.0 :y 0.0 :z 0.0}
               :eye-pos {:x 1.0 :y 64.0 :z 2.0}}
              {:look-dir {:x 0.0 :y 1.0 :z 0.0}
               :eye-pos {:x 1.0 :y 64.0 :z 2.0}}]
             @run-op-inputs*)))))

(deftest clear-player-tasks-prevents-later-execution-test
  (let [run-count* (atom 0)]
    (with-redefs [beam/execute-beam! (fn [_ _]
                                       (swap! run-count* inc)
                                       {:beam-result {:visual-distance 23.0
                                                      :hit-uuids []}})
                  ctx-mgr/push-channel-to-player! (fn [& _] true)
                  ctx-mgr/push-channel-to-nearby-players! (fn [& _] true)
                  md-damage/mark-target! (fn [& _] true)]
      (dp/schedule-scatter-bomb-beam!
       {:player-id "p1"
        :ctx-id "ctx-1"
        :world-id "w"
        :eye {:x 1.0 :y 64.0 :z 2.0}
        :look-dir {:x 0.0 :y 0.0 :z 1.0}
        :damage 7.0
        :beam {:radius 0.3 :query-radius 20.0 :step 0.8 :max-distance 25.0 :visual-distance 23.0}
        :delay-ticks 1})
      (dp/clear-player-tasks! "p1")
      (dp/tick-player! "p1")
      (is (= 0 @run-count*))
      (is (empty? (dp/pending-tasks-snapshot "p1"))))))

(deftest tick-player-with-no-pending-tasks-dispatches-no-command-test
  (testing "idle players (no pending tasks) never reach command-runtime"
    (is (empty? (dp/pending-tasks-snapshot "p1")))
    (let [calls (atom 0)
          original prt-cmd/run-for-player!]
      (with-redefs [prt-cmd/run-for-player!
                    (fn
                      ([uuid cmd] (swap! calls inc) (original uuid cmd {}))
                      ([uuid cmd opts] (swap! calls inc) (original uuid cmd opts)))]
        (dp/tick-player! "p1"))
      (is (zero? @calls)))))


(ns cn.li.ac.ability.server.service.delayed-projectiles-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.server.effect.core :as effect]
            [cn.li.ac.ability.service.delayed-projectiles :as dp]
            [cn.li.ac.content.ability.meltdowner.damage-helper :as md-damage]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.service.context-manager :as ctx-mgr]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]))

(defn- with-fresh-delayed-projectile-runtime [f]
  (dp/call-with-delayed-projectile-runtime (dp/create-delayed-projectile-runtime)
    (fn []
      (try
        (f)
        (finally
          (dp/reset-pending-tasks-for-test!))))))

(use-fixtures :each with-fresh-delayed-projectile-runtime)

(deftest mdball-near-expire-delay-test
  (is (= 15 (dp/mdball-near-expire-delay)))
  (is (= 1 (dp/mdball-near-expire-delay 1)))
  (is (= 5 (dp/mdball-near-expire-delay 10))))

(deftest electron-bomb-settlement-hit-path-test
  (let [calls (atom [])]
    (with-redefs [raycast/*raycast* :mock-raycast
                  raycast/raycast-entities (fn [& _]
                                             {:uuid "target-1"
                                              :x 4.0
                                              :y 65.0
                                              :z 6.0
                                              :distance 9.0})
                  cn.li.mcmod.platform.entity-damage/*entity-damage* :damage
                  entity-damage/apply-direct-damage! (fn [& args]
                                                      (swap! calls conj [:damage args])
                                                      true)
                  md-damage/mark-target! (fn [& args]
                                           (swap! calls conj [:mark args])
                                           true)
                  skill-effects/add-skill-exp! (fn [& args]
                                                 (swap! calls conj [:exp args])
                                                 true)
                  ctx-mgr/push-channel-to-player! (fn [& args]
                                                   (swap! calls conj [:fx args])
                                                   true)
                  ctx-mgr/push-channel-to-nearby-players! (fn [& args]
                                                           (swap! calls conj [:fx-nearby args])
                                                   true)]
      (dp/schedule-electron-bomb-beam!
       {:player-id "p1"
        :ctx-id "ctx-1"
        :world-id "w"
        :eye {:x 1.0 :y 64.0 :z 2.0}
        :look-dir {:x 0.0 :y 0.0 :z 1.0}
        :damage 12.5
        :exp-gain 0.125
        :delay-ticks 1})
      (dp/tick-player! "p1")
      (is (= [[:damage [:damage "w" "target-1" 12.5 :magic]]
          [:mark ["p1" "target-1" {:ctx-id "ctx-1"
                  :target-pos {:x 4.0 :y 65.0 :z 6.0}}]]
              [:exp ["p1" :electron-bomb 0.125]]
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
      (is (nil? (get (dp/pending-tasks-snapshot) "p1"))))))

(deftest electron-bomb-settlement-without-raycast-is-noop-test
  (let [calls (atom [])]
    (with-redefs [raycast/*raycast* nil
                  raycast/raycast-entities (fn [& _]
                                             (swap! calls conj :raycast)
                                             nil)
                  cn.li.mcmod.platform.entity-damage/*entity-damage* :damage
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
        :world-id "w"
        :eye {:x 1.0 :y 64.0 :z 2.0}
        :look-dir nil
        :damage 12.5
        :exp-gain 0.125
        :delay-ticks 1})
      (dp/tick-player! "p1")
      (is (empty? @calls))
      (is (nil? (get (dp/pending-tasks-snapshot) "p1"))))))

(deftest scatter-bomb-settlement-order-and-cleanup-test
  (let [calls (atom [])]
    (with-redefs [effect/run-op! (fn [_ _]
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
      (is (= 1 (count (get (dp/pending-tasks-snapshot) "p1"))))

      (dp/tick-player! "p1")
  (is (= 6 (count @calls)))
      (is (nil? (get (dp/pending-tasks-snapshot) "p1"))))))

(deftest pending-tasks-are-player-keyed-and-clearable-test
  (dp/schedule-task! "p1" 2 {:kind :unknown :payload 1})
  (dp/schedule-task! "p2" 2 {:kind :unknown :payload 2})
  (is (= #{"p1" "p2"} (set (keys (dp/pending-tasks-snapshot)))))
  (dp/clear-player-tasks! "p1")
  (is (nil? (get (dp/pending-tasks-snapshot) "p1")))
  (is (= 1 (count (get (dp/pending-tasks-snapshot) "p2")))))

(deftest delayed-projectile-runtime-isolation-test
  (let [runtime-a (dp/create-delayed-projectile-runtime)
        runtime-b (dp/create-delayed-projectile-runtime)]
    (dp/call-with-delayed-projectile-runtime runtime-a
      (fn []
        (dp/schedule-task! "p1" 2 {:kind :unknown :payload :a})))
    (dp/call-with-delayed-projectile-runtime runtime-b
      (fn []
        (dp/schedule-task! "p1" 2 {:kind :unknown :payload :b})))
    (dp/call-with-delayed-projectile-runtime runtime-b
      (fn []
        (is (= [{:kind :unknown :payload :b :ticks-left 2}]
               (get (dp/pending-tasks-snapshot) "p1")))
        (dp/clear-player-tasks! "p1")
        (is (nil? (get (dp/pending-tasks-snapshot) "p1")))))
    (dp/call-with-delayed-projectile-runtime runtime-a
      (fn []
        (is (= [{:kind :unknown :payload :a :ticks-left 2}]
               (get (dp/pending-tasks-snapshot) "p1")))))))

(deftest scatter-bomb-settlement-uses-task-look-dir-test
  (let [run-op-inputs* (atom [])]
    (with-redefs [effect/run-op! (fn [ctx _op]
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
    (with-redefs [effect/run-op! (fn [_ _]
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
      (is (nil? (get (dp/pending-tasks-snapshot) "p1"))))))

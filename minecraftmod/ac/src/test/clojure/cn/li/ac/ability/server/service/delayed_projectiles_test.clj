(ns cn.li.ac.ability.server.service.delayed-projectiles-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.server.service.delayed-projectiles :as dp]
            [cn.li.ac.content.ability.meltdowner.damage-helper :as md-damage]
            [cn.li.ac.ability.server.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.server.service.context-mgr :as ctx-mgr]
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

(deftest schedule-and-run-tick-test
  (let [hit (atom nil)]
    (with-redefs [cn.li.mcmod.platform.entity-damage/*entity-damage* :damage
                  cn.li.mcmod.platform.entity-damage/apply-direct-damage! (fn [& _] true)]
      (dp/schedule-electron-missile-hit!
       {:player-id "p1"
        :delay-ticks 1
        :world-id "w"
        :target-uuid "e1"
        :damage 3.0
        :on-hit! (fn [uuid] (reset! hit uuid))})
      (dp/tick-player! "p1")
      (is (= "e1" @hit))
      (is (nil? (get (dp/pending-tasks-snapshot) "p1"))))))

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
              [:mark ["p1" "target-1"]]
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

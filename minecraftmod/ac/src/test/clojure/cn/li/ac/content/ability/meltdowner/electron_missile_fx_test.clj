(ns cn.li.ac.content.ability.meltdowner.electron-missile-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.content.ability.meltdowner.electron-missile-fx :as em-fx]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(defn- reset-fixture [f]
  (binding [runtime-hooks/*client-session-id* :test-session]
    (level-effects/call-with-level-effect-runtime
      (level-effects/create-level-effect-runtime)
      (fn []
        (try
          (level-effects/reset-level-effect-registry-for-test!)
          (em-fx/reset-electron-missile-fx-for-test!)
          (f)
          (finally
            (em-fx/reset-electron-missile-fx-for-test!)
            (level-effects/reset-level-effect-registry-for-test!)))))))

(use-fixtures :each reset-fixture)

(defn- event
  [ctx-id channel payload]
  {:payload payload
   :ctx-id ctx-id
   :channel channel
   :owner-key [:ctx ctx-id]})

(deftest fx-handler-routes-events-with-ctx-metadata-test
  (let [handler* (atom nil)
        enqueued* (atom [])]
    (with-redefs [level-effects/register-level-effect! (fn [& _] nil)
                  fx-registry/register-fx-channels! (fn [_ handler]
                                                      (reset! handler* handler)
                                                      nil)
                  level-effects/enqueue-level-effect! (fn [effect-id payload fx-context]
                                                        (swap! enqueued* conj [effect-id payload fx-context])
                                                        nil)]
      (em-fx/init!)
      (@handler* "ctx-em" :electron-missile/fx-start {:source-player-id "player-a"})
      (@handler* "ctx-em" :electron-missile/fx-update {:ticks 12
                                                        :balls 3
                                                        :source-player-id "player-a"})
      (@handler* "ctx-em" :electron-missile/fx-fire {:target-x 1.0
                                                      :target-y 64.0
                                                      :target-z 2.0
                                                      :start {:x 0.0 :y 64.0 :z 0.0}
                                                      :end {:x 1.0 :y 65.5 :z 2.0}
                                                      :source-player-id "player-a"})
      (@handler* "ctx-em" :electron-missile/fx-end {:source-player-id "player-a"})
      (is (= [[:electron-missile {:mode :start :source-player-id "player-a"}
               {:ctx-id "ctx-em" :channel :electron-missile/fx-start}]
              [:electron-missile {:mode :update
                                  :ticks 12
                                  :balls 3
                                  :source-player-id "player-a"}
               {:ctx-id "ctx-em" :channel :electron-missile/fx-update}]
              [:electron-missile {:mode :fire
                                  :start {:x 0.0 :y 64.0 :z 0.0}
                                  :end {:x 1.0 :y 65.5 :z 2.0}
                                  :target-x 1.0
                                  :target-y 64.0
                                  :target-z 2.0
                                  :source-player-id "player-a"}
               {:ctx-id "ctx-em" :channel :electron-missile/fx-fire}]
              [:electron-missile {:mode :end :source-player-id "player-a"}
               {:ctx-id "ctx-em" :channel :electron-missile/fx-end}]]
             @enqueued*)))))

(deftest fire-adds-beam-and-end-clears-state-test
  (let [enqueue-state! (var-get #'cn.li.ac.content.ability.meltdowner.electron-missile-fx/enqueue-state!)
        tick-state! (var-get #'cn.li.ac.content.ability.meltdowner.electron-missile-fx/tick-state!)
        particles* (atom [])
        sounds* (atom [])]
    (with-redefs [client-particles/current-effect-owner (fn [] {:client-session-id "electron-missile-fx-test"})
                  client-particles/queue-particle-effect! (fn [& args]
                                                             (swap! particles* conj args)
                                                             nil)
                  client-sounds/queue-sound-effect! (fn [& args]
                                                        (swap! sounds* conj args)
                                                        nil)]
      (level-effects/update-effect-state! :electron-missile
        enqueue-state!
        (event "ctx-a" :electron-missile/fx-update
               {:mode :update
                :ticks 8
                :balls 2
                :source-player-id "player-a"}))
      (is (= 2 (get-in (em-fx/electron-missile-fx-snapshot) [:charge-state [:ctx "ctx-a"] :balls])))
      (level-effects/update-effect-state! :electron-missile
        enqueue-state!
        (event "ctx-a" :electron-missile/fx-fire
               {:mode :fire
                :start {:x 0.0 :y 64.0 :z 0.0}
                :end {:x 1.0 :y 65.5 :z 2.0}
                :target-x 1.0 :target-y 64.0 :target-z 2.0
                :source-player-id "player-a"}))
      (is (seq (get-in (em-fx/electron-missile-fx-snapshot) [:beams [:ctx "ctx-a"]])))
      (dotimes [_ 10]
        (level-effects/update-effect-state! :electron-missile
          (fn [store _]
            (tick-state! store))
          nil))
      (is (empty? (get-in (em-fx/electron-missile-fx-snapshot) [:beams [:ctx "ctx-a"]])))
      (level-effects/update-effect-state! :electron-missile
        enqueue-state!
        (event "ctx-a" :electron-missile/fx-end
               {:mode :end
                :source-player-id "player-a"}))
      (is (nil? (get-in (em-fx/electron-missile-fx-snapshot) [:charge-state [:ctx "ctx-a"]])))
      (is (seq @particles*))
      (is (seq @sounds*)))))

(deftest beam-impact-ttl-cadence-test
  (let [enqueue-state! (var-get #'cn.li.ac.content.ability.meltdowner.electron-missile-fx/enqueue-state!)
        tick-state! (var-get #'cn.li.ac.content.ability.meltdowner.electron-missile-fx/tick-state!)
        build-plan (var-get #'cn.li.ac.content.ability.meltdowner.electron-missile-fx/build-plan)
        particles* (atom [])]
    (with-redefs [client-particles/current-effect-owner (fn [] {:client-session-id "electron-missile-fx-test"})
                  client-particles/queue-particle-effect! (fn [& args]
                                                            (swap! particles* conj args)
                                                            nil)
                  client-sounds/queue-sound-effect! (fn [& _] nil)]
      (level-effects/update-effect-state! :electron-missile
        enqueue-state!
        (event "ctx-em" :electron-missile/fx-fire
               {:mode :fire
                :start {:x 0.0 :y 64.0 :z 0.0}
                :end {:x 1.0 :y 65.5 :z 2.0}
                :source-player-id "player-a"}))
      (level-effects/update-effect-state! :electron-missile
        enqueue-state!
        (event "ctx-em" :electron-missile/fx-fire
               {:mode :fire
                :target-x 4.0 :target-y 64.0 :target-z 4.0
                :source-player-id "player-a"}))

      (is (= 10 (get-in (em-fx/electron-missile-fx-snapshot) [:beams [:ctx "ctx-em"] 0 :ttl])))
      (is (= 10 (get-in (em-fx/electron-missile-fx-snapshot) [:impacts [:ctx "ctx-em"] 0 :ttl])))

      (level-effects/update-effect-state! :electron-missile
        (fn [store _]
          (tick-state! store))
        nil)

      (is (= 9 (get-in (em-fx/electron-missile-fx-snapshot) [:beams [:ctx "ctx-em"] 0 :ttl])))
      (is (= 9 (get-in (em-fx/electron-missile-fx-snapshot) [:impacts [:ctx "ctx-em"] 0 :ttl])))
      (is (some? (build-plan nil nil 0)))
      (is (= 2 (count @particles*))
          "one impact spark and one beam-end spark should be emitted per tick while both entries are alive")

      (dotimes [_ 9]
        (level-effects/update-effect-state! :electron-missile
          (fn [store _]
            (tick-state! store))
          nil))

      (is (nil? (get-in (em-fx/electron-missile-fx-snapshot) [:beams [:ctx "ctx-em"]])))
      (is (nil? (get-in (em-fx/electron-missile-fx-snapshot) [:impacts [:ctx "ctx-em"]])))
      (is (nil? (build-plan nil nil 0))))))

(deftest electron-missile-fx-runtime-isolation-test
  (let [runtime-a (level-effects/create-level-effect-runtime)
        runtime-b (level-effects/create-level-effect-runtime)
        enqueue-state! (var-get #'cn.li.ac.content.ability.meltdowner.electron-missile-fx/enqueue-state!)]
    (with-redefs [client-particles/current-effect-owner (fn [] {:client-session-id "electron-missile-fx-test"})
                  client-particles/queue-particle-effect! (fn [& _] nil)
                  client-sounds/queue-sound-effect! (fn [& _] nil)]
      (level-effects/call-with-level-effect-runtime
        runtime-a
        (fn []
          (level-effects/update-effect-state! :electron-missile
            enqueue-state!
               (event "ctx-a" :electron-missile/fx-fire
                 {:mode :fire
                  :start {:x 0.0 :y 64.0 :z 0.0}
                  :end {:x 1.0 :y 65.5 :z 2.0}
                  :target-x 1.0 :target-y 64.0 :target-z 2.0
                  :source-player-id "player-a"}))
          (is (= #{[:ctx "ctx-a"]}
               (set (keys (:beams (em-fx/electron-missile-fx-snapshot))))))))
      (level-effects/call-with-level-effect-runtime
        runtime-b
        (fn []
             (is (= {:charge-state {} :beams {} :impacts {}}
                 (em-fx/electron-missile-fx-snapshot)))
          (level-effects/update-effect-state! :electron-missile
            enqueue-state!
               (event "ctx-b" :electron-missile/fx-fire
                 {:mode :fire
                  :start {:x 9.0 :y 64.0 :z 0.0}
                  :end {:x 10.0 :y 65.5 :z 2.0}
                  :target-x 10.0 :target-y 64.0 :target-z 2.0
                  :source-player-id "player-b"}))
          (is (= #{[:ctx "ctx-b"]}
               (set (keys (:beams (em-fx/electron-missile-fx-snapshot))))))))
      (level-effects/call-with-level-effect-runtime
        runtime-a
        (fn []
          (is (= #{[:ctx "ctx-a"]}
               (set (keys (:beams (em-fx/electron-missile-fx-snapshot)))))))))))

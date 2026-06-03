(ns cn.li.ac.content.ability.meltdowner.electron-bomb-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.content.ability.meltdowner.electron-bomb-fx :as electron-bomb-fx]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(defn- reset-fixture [f]
  (binding [runtime-hooks/*client-session-id* :test-session]
    (level-effects/call-with-level-effect-runtime
      (level-effects/create-level-effect-runtime)
      (fn []
        (try
          (level-effects/reset-level-effect-registry-for-test!)
          (electron-bomb-fx/reset-electron-bomb-fx-for-test!)
          (f)
          (finally
            (electron-bomb-fx/reset-electron-bomb-fx-for-test!)
            (level-effects/reset-level-effect-registry-for-test!)))))))

(use-fixtures :each reset-fixture)

(defn- event [ctx-id channel payload]
  {:payload payload
   :ctx-id ctx-id
   :channel channel
   :owner-key [:ctx ctx-id]})

(deftest init-registers-owner-aware-electron-bomb-fx-test
  (let [registered-level* (atom nil)
        registered-handler* (atom nil)]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                         (reset! registered-level* [effect-id effect-map])
                                                         nil)
                  fx-registry/register-fx-channels! (fn [channels handler]
                                                      (reset! registered-handler* {:channels channels
                                                                                   :handler handler})
                                                      nil)]
      (electron-bomb-fx/init!)
      (is (= :electron-bomb (first @registered-level*)))
      (is (fn? (:enqueue-state-fn (second @registered-level*))))
      (is (= #{:electron-bomb/fx-spawn
               :electron-bomb/fx-beam
               :electron-bomb/fx-end}
             (set (:channels @registered-handler*)))))))

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
      (electron-bomb-fx/init!)
      (@handler* "ctx-eb" :electron-bomb/fx-spawn {:x 1.0 :y 64.0 :z 2.0 :dx 0.0 :dy 0.0 :dz 1.0})
      (@handler* "ctx-eb" :electron-bomb/fx-beam {:start {:x 1.0 :y 64.0 :z 2.0}
                                                   :end {:x 1.0 :y 64.0 :z 17.0}
                                                   :performed? true
                                                   :target-uuid "target-1"})
      (@handler* "ctx-eb" :electron-bomb/fx-end {})
      (is (= [[:electron-bomb {:mode :spawn
                               :x 1.0 :y 64.0 :z 2.0
                               :dx 0.0 :dy 0.0 :dz 1.0}
               {:ctx-id "ctx-eb" :channel :electron-bomb/fx-spawn}]
              [:electron-bomb {:mode :beam
                               :start {:x 1.0 :y 64.0 :z 2.0}
                               :end {:x 1.0 :y 64.0 :z 17.0}}
               {:ctx-id "ctx-eb" :channel :electron-bomb/fx-beam}]
              [:electron-bomb {:mode :end}
               {:ctx-id "ctx-eb" :channel :electron-bomb/fx-end}]]
             @enqueued*)))))

(deftest spawn-beam-and-tick-state-test
  (let [enqueue-state! (var-get #'cn.li.ac.content.ability.meltdowner.electron-bomb-fx/enqueue-state!)
        tick-state! (var-get #'cn.li.ac.content.ability.meltdowner.electron-bomb-fx/tick-state!)
        build-plan (var-get #'cn.li.ac.content.ability.meltdowner.electron-bomb-fx/build-plan)
        particles* (atom [])
        sounds* (atom [])]
    (with-redefs [client-particles/current-effect-owner (fn [] {:client-session-id "electron-bomb-fx-test"})
                  client-particles/queue-particle-effect! (fn [& args]
                                                             (swap! particles* conj args)
                                                             nil)
                  client-sounds/queue-sound-effect! (fn [& args]
                                                        (swap! sounds* conj args)
                                                        nil)]
      (level-effects/update-effect-state! :electron-bomb
        enqueue-state!
        (event "ctx-a" :electron-bomb/fx-spawn
               {:mode :spawn :x 1.0 :y 64.0 :z 2.0 :dx 0.0 :dy 0.0 :dz 1.0}))
      (is (some? (get-in (electron-bomb-fx/electron-bomb-fx-snapshot) [:effect-state [:ctx "ctx-a"]])))
      (let [spawn-plan (build-plan {:x 0.0 :y 65.0 :z 0.0} nil 0)]
        (is (seq (:ops spawn-plan))))
      (level-effects/update-effect-state! :electron-bomb
        enqueue-state!
        (event "ctx-a" :electron-bomb/fx-beam
               {:mode :beam
                :start {:x 1.0 :y 64.0 :z 2.0}
                :end {:x 1.0 :y 64.0 :z 17.0}
                :performed? true
                :target-uuid "target-1"}))
      (let [snapshot (electron-bomb-fx/electron-bomb-fx-snapshot)]
        (is (nil? (get-in snapshot [:effect-state [:ctx "ctx-a"]])))
        (is (seq (get-in snapshot [:beams [:ctx "ctx-a"]]))))
      (is (seq (:ops (build-plan {:x 0.0 :y 65.0 :z 0.0} nil 0))))
      (dotimes [_ 9]
        (level-effects/update-effect-state! :electron-bomb
          (fn [store _]
            (tick-state! store))
          nil))
      (is (nil? (build-plan {:x 0.0 :y 65.0 :z 0.0} nil 0)))
      (is (seq @particles*))
      (is (seq @sounds*)))))

(deftest electron-bomb-active-and-beam-cadence-test
  (let [enqueue-state! (var-get #'cn.li.ac.content.ability.meltdowner.electron-bomb-fx/enqueue-state!)
        tick-state! (var-get #'cn.li.ac.content.ability.meltdowner.electron-bomb-fx/tick-state!)
        build-plan (var-get #'cn.li.ac.content.ability.meltdowner.electron-bomb-fx/build-plan)
        particles* (atom [])]
    (with-redefs [client-particles/current-effect-owner (fn [] {:client-session-id "electron-bomb-fx-test"})
                  client-particles/queue-particle-effect! (fn [& args]
                                                            (swap! particles* conj args)
                                                            nil)
                  client-sounds/queue-sound-effect! (fn [& _] nil)]
      (level-effects/update-effect-state! :electron-bomb
        enqueue-state!
        (event "ctx-cadence" :electron-bomb/fx-spawn
               {:mode :spawn :x 1.0 :y 64.0 :z 2.0 :dx 0.0 :dy 0.0 :dz 1.0}))

      (dotimes [_ 41]
        (level-effects/update-effect-state! :electron-bomb
          (fn [store _]
            (tick-state! store))
          nil))

      (is (nil? (get-in (electron-bomb-fx/electron-bomb-fx-snapshot) [:effect-state [:ctx "ctx-cadence"]]))
          "active electron-bomb state should auto-expire after tick > 40")
      (is (= 13 (count @particles*))
          "orbit particles should be emitted on ticks 3,6,...,39")

      (reset! particles* [])
      (level-effects/update-effect-state! :electron-bomb
        enqueue-state!
        (event "ctx-cadence" :electron-bomb/fx-beam
               {:mode :beam
                :start {:x 1.0 :y 64.0 :z 2.0}
                :end {:x 1.0 :y 64.0 :z 17.0}}))
      (is (seq (:ops (build-plan {:x 0.0 :y 65.0 :z 0.0} nil 0))))
      (dotimes [_ 8]
        (level-effects/update-effect-state! :electron-bomb
          (fn [store _]
            (tick-state! store))
          nil))
      (is (nil? (build-plan {:x 0.0 :y 65.0 :z 0.0} nil 0))
          "beam flash plan should disappear when ttl decays to zero"))))

(deftest electron-bomb-fx-runtime-isolation-test
  (let [runtime-a (level-effects/create-level-effect-runtime)
        runtime-b (level-effects/create-level-effect-runtime)
        enqueue-state! (var-get #'cn.li.ac.content.ability.meltdowner.electron-bomb-fx/enqueue-state!)]
    (with-redefs [client-particles/current-effect-owner (fn [] {:client-session-id "electron-bomb-fx-test"})
                  client-particles/queue-particle-effect! (fn [& _] nil)
                  client-sounds/queue-sound-effect! (fn [& _] nil)]
      (level-effects/call-with-level-effect-runtime
        runtime-a
        (fn []
          (level-effects/update-effect-state! :electron-bomb
            enqueue-state!
            (event "ctx-a" :electron-bomb/fx-spawn
                   {:mode :spawn :x 1.0 :y 64.0 :z 2.0 :dx 0.0 :dy 0.0 :dz 1.0}))
          (is (= #{[:ctx "ctx-a"]}
                 (set (keys (:effect-state (electron-bomb-fx/electron-bomb-fx-snapshot))))))))
      (level-effects/call-with-level-effect-runtime
        runtime-b
        (fn []
          (is (= {:effect-state {}
                  :beams {}}
                 (electron-bomb-fx/electron-bomb-fx-snapshot)))
          (level-effects/update-effect-state! :electron-bomb
            enqueue-state!
            (event "ctx-b" :electron-bomb/fx-spawn
                   {:mode :spawn :x 10.0 :y 64.0 :z 2.0 :dx 0.0 :dy 0.0 :dz 1.0}))
          (is (= #{[:ctx "ctx-b"]}
                 (set (keys (:effect-state (electron-bomb-fx/electron-bomb-fx-snapshot))))))))
      (level-effects/call-with-level-effect-runtime
        runtime-a
        (fn []
          (is (= #{[:ctx "ctx-a"]}
                 (set (keys (:effect-state (electron-bomb-fx/electron-bomb-fx-snapshot)))))))))))

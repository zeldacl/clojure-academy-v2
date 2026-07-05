(ns cn.li.ac.content.ability.meltdowner.electron-bomb-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.content.ability.meltdowner.electron-bomb-fx :as electron-bomb-fx]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(defn- reset-fixture [f]
  (runtime-hooks/with-client-ctx {:session-id :test-session}
    (try
          (level-effects/reset-level-effect-registry-for-test!)
          (electron-bomb-fx/reset-electron-bomb-fx-for-test!)
          (f)
          (finally
            (electron-bomb-fx/reset-electron-bomb-fx-for-test!)
            (level-effects/reset-level-effect-registry-for-test!)))))

(use-fixtures :each reset-fixture)

(defn- event [ctx-id channel payload]
  {:payload payload
   :ctx-id ctx-id
   :channel channel
   :owner-key [:ctx ctx-id]})

(deftest init-registers-owner-aware-electron-bomb-fx-test
  (let [registered-level* (atom nil)
        registered-topics* (atom #{})]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                         (reset! registered-level* [effect-id effect-map])
                                                         nil)
                  fx-registry/register-fx-channel! (fn [topic _handler]
                                                      (swap! registered-topics* conj topic)
                                                      nil)]
      (electron-bomb-fx/init!)
      (is (= :electron-bomb (first @registered-level*)))
      (is (fn? (:enqueue-state-fn (second @registered-level*))))
      (is (= #{:electron-bomb/fx-spawn
               :electron-bomb/fx-beam
               :electron-bomb/fx-end}
             @registered-topics*)))))

(deftest fx-handler-routes-events-with-ctx-metadata-test
  (let [handlers* (atom {})
        enqueued* (atom [])]
    (with-redefs [level-effects/register-level-effect! (fn [& _] nil)
                  fx-registry/register-fx-channel! (fn [topic handler]
                                                      (swap! handlers* assoc topic handler)
                                                      nil)
                  level-effects/enqueue-level-effect! (fn [effect-id ctx-id channel payload & opts]
                                                        (swap! enqueued* conj [effect-id ctx-id channel payload opts])
                                                        nil)]
      (electron-bomb-fx/init!)
      ((get @handlers* :electron-bomb/fx-spawn) "ctx-eb" :electron-bomb/fx-spawn {:x 1.0 :y 64.0 :z 2.0 :dx 0.0 :dy 0.0 :dz 1.0})
      ((get @handlers* :electron-bomb/fx-beam) "ctx-eb" :electron-bomb/fx-beam {:start {:x 1.0 :y 64.0 :z 2.0}
                                                   :end {:x 1.0 :y 64.0 :z 17.0}
                                                   :performed? true
                                                   :target-uuid "target-1"})
      ((get @handlers* :electron-bomb/fx-end) "ctx-eb" :electron-bomb/fx-end {})
      (is (= [[:electron-bomb {:mode :spawn
                               :owner-key [:ctx "ctx-eb"]
                               :ctx-id "ctx-eb"
                               :channel :electron-bomb/fx-spawn
                               :x 1.0 :y 64.0 :z 2.0
                               :dx 0.0 :dy 0.0 :dz 1.0}
               {:ctx-id "ctx-eb"
                :channel :electron-bomb/fx-spawn
                :owner-key [:ctx "ctx-eb"]}]
              [:electron-bomb {:mode :beam
                               :owner-key [:ctx "ctx-eb"]
                               :ctx-id "ctx-eb"
                               :channel :electron-bomb/fx-beam
                               :start {:x 1.0 :y 64.0 :z 2.0}
                               :end {:x 1.0 :y 64.0 :z 17.0}
                               :performed? true
                               :target-uuid "target-1"}
               {:ctx-id "ctx-eb"
                :channel :electron-bomb/fx-beam
                :owner-key [:ctx "ctx-eb"]}]
              [:electron-bomb {:mode :end
                               :owner-key [:ctx "ctx-eb"]
                               :ctx-id "ctx-eb"
                               :channel :electron-bomb/fx-end}
               {:ctx-id "ctx-eb"
                :channel :electron-bomb/fx-end
                :owner-key [:ctx "ctx-eb"]}]]
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



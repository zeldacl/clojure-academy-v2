(ns cn.li.ac.content.ability.meltdowner.mine-ray-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.content.ability.meltdowner.mine-ray-fx :as mr-fx]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(defn- reset-fixture [f]
  (binding [runtime-hooks/*client-session-id* :test-session]
    (level-effects/call-with-level-effect-runtime
      (level-effects/create-level-effect-runtime)
      (fn []
        (try
          (level-effects/reset-level-effect-registry-for-test!)
          (mr-fx/reset-mine-ray-fx-for-test!)
          (f)
          (finally
            (mr-fx/reset-mine-ray-fx-for-test!)
            (level-effects/reset-level-effect-registry-for-test!)))))))

(use-fixtures :each reset-fixture)

(defn- event
  [ctx-id channel payload]
  {:payload payload
   :ctx-id ctx-id
   :channel channel
   :owner-key [:ctx ctx-id]})

(deftest init-registers-owner-aware-mine-ray-fx-test
  (let [registered-level* (atom nil)
        registered-handler* (atom nil)]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                         (reset! registered-level* [effect-id effect-map])
                                                         nil)
                  fx-registry/register-fx-channels! (fn [channels handler]
                                                      (reset! registered-handler* {:channels channels
                                                                                   :handler handler})
                                                      nil)]
      (mr-fx/init!)
      (is (= :mine-ray (first @registered-level*)))
      (is (fn? (:enqueue-state-fn (second @registered-level*))))
      (is (= #{:mine-ray/fx-start
               :mine-ray/fx-progress
               :mine-ray/fx-end}
             (set (:channels @registered-handler*)))))))

(deftest start-progress-tick-end-manage-state-test
  (let [enqueue-state! (var-get #'cn.li.ac.content.ability.meltdowner.mine-ray-fx/enqueue-state!)
        tick-state! (var-get #'cn.li.ac.content.ability.meltdowner.mine-ray-fx/tick-state!)
        build-plan (var-get #'cn.li.ac.content.ability.meltdowner.mine-ray-fx/build-plan)
        particles* (atom [])
        sounds* (atom [])]
    (with-redefs [client-particles/current-effect-owner (fn [] {:client-session-id "mine-ray-fx-test"})
                  client-particles/queue-particle-effect! (fn [& args]
                                                             (swap! particles* conj args)
                                                             nil)
                  client-sounds/queue-sound-effect! (fn [& args]
                                                        (swap! sounds* conj args)
                                                        nil)]
      (level-effects/update-effect-state! :mine-ray
        enqueue-state!
        (event "ctx-mr" :mine-ray/fx-start {:mode :start :variant :expert :source-player-id "player-a"}))
      (level-effects/update-effect-state! :mine-ray
        enqueue-state!
        (event "ctx-mr" :mine-ray/fx-progress {:mode :progress
                                                 :x 2 :y 64 :z 5
                                                 :progress 0.5
                                                 :source-player-id "player-a"}))
      (is (= {:x 2 :y 64 :z 5}
             (get-in (mr-fx/mine-ray-fx-snapshot) [:effect-state [:ctx "ctx-mr"] :target])))
      (is (seq (:ops (build-plan {:x 0.0 :y 65.0 :z 0.0} nil 0))))
      (dotimes [_ 8]
        (level-effects/update-effect-state! :mine-ray
          (fn [store _]
            (tick-state! store))
          nil))
      (is (seq @particles*))
      (level-effects/update-effect-state! :mine-ray
        enqueue-state!
        (event "ctx-mr" :mine-ray/fx-end {:mode :end :source-player-id "player-a"}))
      (is (nil? (get-in (mr-fx/mine-ray-fx-snapshot) [:effect-state [:ctx "ctx-mr"]])))
      (is (seq @sounds*)))))

(deftest mine-ray-particle-cadence-test
  (let [enqueue-state! (var-get #'cn.li.ac.content.ability.meltdowner.mine-ray-fx/enqueue-state!)
        tick-state! (var-get #'cn.li.ac.content.ability.meltdowner.mine-ray-fx/tick-state!)
        particles* (atom [])]
    (with-redefs [client-particles/current-effect-owner (fn [] {:client-session-id "mine-ray-fx-test"})
                  client-particles/queue-particle-effect! (fn [& args]
                                                            (swap! particles* conj args)
                                                            nil)
                  client-sounds/queue-sound-effect! (fn [& _] nil)]
      (level-effects/update-effect-state! :mine-ray
        enqueue-state!
        (event "ctx-cadence" :mine-ray/fx-start {:mode :start :variant :basic :source-player-id "player-a"}))
      (level-effects/update-effect-state! :mine-ray
        enqueue-state!
        (event "ctx-cadence" :mine-ray/fx-progress {:mode :progress
                                                     :x 2 :y 64 :z 5
                                                     :progress 0.2
                                                     :source-player-id "player-a"}))

      (dotimes [_ 16]
        (level-effects/update-effect-state! :mine-ray
          (fn [store _]
            (tick-state! store))
          nil))

      (is (= 16 (get-in (mr-fx/mine-ray-fx-snapshot) [:effect-state [:ctx "ctx-cadence"] :ticks])))
      (is (= 2 (count @particles*))
          "mine-ray should emit target particles every 8 ticks while active")

      (level-effects/update-effect-state! :mine-ray
        enqueue-state!
        (event "ctx-cadence" :mine-ray/fx-end {:mode :end :source-player-id "player-a"}))
      (is (nil? (get-in (mr-fx/mine-ray-fx-snapshot) [:effect-state [:ctx "ctx-cadence"]]))))))

(deftest mine-ray-fx-runtime-isolation-test
  (let [runtime-a (level-effects/create-level-effect-runtime)
        runtime-b (level-effects/create-level-effect-runtime)
        enqueue-state! (var-get #'cn.li.ac.content.ability.meltdowner.mine-ray-fx/enqueue-state!)]
    (with-redefs [client-particles/current-effect-owner (fn [] {:client-session-id "mine-ray-fx-test"})
                  client-particles/queue-particle-effect! (fn [& _] nil)
                  client-sounds/queue-sound-effect! (fn [& _] nil)]
      (level-effects/call-with-level-effect-runtime
        runtime-a
        (fn []
          (level-effects/update-effect-state! :mine-ray
            enqueue-state!
            (event "ctx-a" :mine-ray/fx-start {:mode :start :variant :luck :source-player-id "player-a"}))
          (is (= #{[:ctx "ctx-a"]}
                 (set (keys (:effect-state (mr-fx/mine-ray-fx-snapshot))))))))
      (level-effects/call-with-level-effect-runtime
        runtime-b
        (fn []
          (is (= {:effect-state {}}
                 (mr-fx/mine-ray-fx-snapshot)))
          (level-effects/update-effect-state! :mine-ray
            enqueue-state!
            (event "ctx-b" :mine-ray/fx-start {:mode :start :variant :luck :source-player-id "player-b"}))
          (is (= #{[:ctx "ctx-b"]}
                 (set (keys (:effect-state (mr-fx/mine-ray-fx-snapshot))))))))
      (level-effects/call-with-level-effect-runtime
        runtime-a
        (fn []
          (is (= #{[:ctx "ctx-a"]}
                 (set (keys (:effect-state (mr-fx/mine-ray-fx-snapshot)))))))))))

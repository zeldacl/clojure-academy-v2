(ns cn.li.ac.content.ability.meltdowner.light-shield-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.content.ability.meltdowner.light-shield-fx :as ls-fx]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(defn- reset-fixture [f]
  (runtime-hooks/with-client-ctx {:session-id :test-session}
    (try
          (level-effects/reset-level-effect-registry-for-test!)
          (ls-fx/reset-light-shield-fx-for-test!)
          (f)
          (finally
            (ls-fx/reset-light-shield-fx-for-test!)
            (level-effects/reset-level-effect-registry-for-test!)))))

(use-fixtures :each reset-fixture)

(defn- event
  [ctx-id channel payload]
  {:payload payload
   :ctx-id ctx-id
   :channel channel
   :owner-key [:ctx ctx-id]})

(deftest init-registers-owner-aware-light-shield-fx-test
  (let [registered-level* (atom nil)
        registered-topics* (atom #{})]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                         (reset! registered-level* [effect-id effect-map])
                                                         nil)
                  fx-registry/register-fx-channel! (fn [topic _handler]
                                                      (swap! registered-topics* conj topic)
                                                      nil)]
      (ls-fx/init!)
      (is (= :light-shield (first @registered-level*)))
      (is (fn? (:enqueue-state-fn (second @registered-level*))))
      (is (= #{:light-shield/fx-start
               :light-shield/fx-end}
             @registered-topics*)))))

(deftest start-end-update-state-and-build-plan-test
  (let [enqueue-state! (var-get #'cn.li.ac.content.ability.meltdowner.light-shield-fx/enqueue-state!)
  build-plan (var-get #'cn.li.ac.content.ability.meltdowner.light-shield-fx/build-plan)
        tick-state! (var-get #'cn.li.ac.content.ability.meltdowner.light-shield-fx/tick-state!)
        particles* (atom [])
        sounds* (atom [])]
    (with-redefs [client-particles/current-effect-owner (fn [] {:client-session-id "light-shield-fx-test"})
                  client-particles/queue-particle-effect! (fn [& args]
                                                             (swap! particles* conj args)
                                                             nil)
                  client-sounds/queue-sound-effect! (fn [& args]
                                                        (swap! sounds* conj args)
                                                        nil)]
      (level-effects/update-effect-state! :light-shield
        enqueue-state!
        (event "ctx-ls" :light-shield/fx-start {:mode :start :source-player-id "player-a"}))
      (is (some? (get-in (ls-fx/light-shield-fx-snapshot) [:effect-state [:ctx "ctx-ls"]])))
      (dotimes [_ 5]
        (level-effects/update-effect-state! :light-shield
          (fn [store _]
            (tick-state! store))
          nil))
      (is (seq @particles*))
      (is (map? (build-plan {:x 0.0 :y 64.0 :z 0.0} {:player-uuid "player-a" :x 0.0 :y 64.0 :z 0.0} 12)))
      (level-effects/update-effect-state! :light-shield
        enqueue-state!
        (event "ctx-ls" :light-shield/fx-end {:mode :end :source-player-id "player-a"}))
      (is (nil? (get-in (ls-fx/light-shield-fx-snapshot) [:effect-state [:ctx "ctx-ls"]])))
      (is (seq @sounds*)))))

(deftest light-shield-particle-cadence-test
  (let [enqueue-state! (var-get #'cn.li.ac.content.ability.meltdowner.light-shield-fx/enqueue-state!)
        tick-state! (var-get #'cn.li.ac.content.ability.meltdowner.light-shield-fx/tick-state!)
        particles* (atom [])]
    (with-redefs [client-particles/current-effect-owner (fn [] {:client-session-id "light-shield-fx-test"})
                  client-particles/queue-particle-effect! (fn [& args]
                                                            (swap! particles* conj args)
                                                            nil)
                  client-sounds/queue-sound-effect! (fn [& _] nil)]
      (level-effects/update-effect-state! :light-shield
        enqueue-state!
        (event "ctx-cadence" :light-shield/fx-start {:mode :start :source-player-id "player-a"}))

      (dotimes [_ 10]
        (level-effects/update-effect-state! :light-shield
          (fn [store _]
            (tick-state! store))
          nil))

      (is (= 10 (get-in (ls-fx/light-shield-fx-snapshot) [:effect-state [:ctx "ctx-cadence"] :ticks])))
      (is (= 2 (count @particles*))
          "light-shield should emit particles every 5 ticks")

      (level-effects/update-effect-state! :light-shield
        enqueue-state!
        (event "ctx-cadence" :light-shield/fx-end {:mode :end :source-player-id "player-a"}))
      (is (nil? (get-in (ls-fx/light-shield-fx-snapshot) [:effect-state [:ctx "ctx-cadence"]]))))))



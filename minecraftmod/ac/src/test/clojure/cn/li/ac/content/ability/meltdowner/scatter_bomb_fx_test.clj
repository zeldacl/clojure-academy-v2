(ns cn.li.ac.content.ability.meltdowner.scatter-bomb-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.content.ability.meltdowner.scatter-bomb-fx :as sb-fx]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(defn- reset-fixture [f]
  (runtime-hooks/with-client-ctx {:session-id :test-session}
    (try
          (level-effects/reset-level-effect-registry-for-test!)
          (sb-fx/reset-scatter-bomb-fx-for-test!)
          (f)
          (finally
            (sb-fx/reset-scatter-bomb-fx-for-test!)
            (level-effects/reset-level-effect-registry-for-test!)))))

(use-fixtures :each reset-fixture)

(defn- event
  [ctx-id channel payload]
  {:payload payload
   :ctx-id ctx-id
   :channel channel
   :owner-key [:ctx ctx-id]})

(deftest init-registers-owner-aware-scatter-bomb-fx-test
  (let [registered-level* (atom nil)
        registered-topics* (atom #{})]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                         (reset! registered-level* [effect-id effect-map])
                                                         nil)
                  fx-registry/register-fx-channel! (fn [topic _handler]
                                                      (swap! registered-topics* conj topic)
                                                      nil)]
      (sb-fx/init!)
      (is (= :scatter-bomb (first @registered-level*)))
      (is (fn? (:enqueue-state-fn (second @registered-level*))))
      (is (= #{:scatter-bomb/fx-start
               :scatter-bomb/fx-ball
               :scatter-bomb/fx-beam
               :scatter-bomb/fx-end}
             @registered-topics*)))))

(deftest start-ball-beam-end-manage-state-test
  (let [enqueue-state! (var-get #'cn.li.ac.content.ability.meltdowner.scatter-bomb-fx/enqueue-state!)
        tick-state! (var-get #'cn.li.ac.content.ability.meltdowner.scatter-bomb-fx/tick-state!)
        particles* (atom [])
        sounds* (atom [])]
    (with-redefs [client-particles/queue-current-particle-effect! (fn [& args]
                                                                     (swap! particles* conj args)
                                                                     nil)
                  client-sounds/queue-current-sound-effect! (fn [& args]
                                                                (swap! sounds* conj args)
                                                                nil)]
      (level-effects/update-effect-state! :scatter-bomb
        enqueue-state!
        (event "ctx-sb" :scatter-bomb/fx-start {:mode :start :source-player-id "player-a"}))
      (level-effects/update-effect-state! :scatter-bomb
        enqueue-state!
        (event "ctx-sb" :scatter-bomb/fx-ball {:mode :ball
                                                :x 1.0 :y 64.0 :z 2.0
                                                :count 3
                                                :source-player-id "player-a"}))
      (is (= 3 (get-in (sb-fx/scatter-bomb-fx-snapshot) [:effect-state [:ctx "ctx-sb"] :balls])))
      (level-effects/update-effect-state! :scatter-bomb
        (fn [store _]
          (tick-state! store))
        nil)
      (is (= 1 (get-in (sb-fx/scatter-bomb-fx-snapshot) [:effect-state [:ctx "ctx-sb"] :ticks])))
      (level-effects/update-effect-state! :scatter-bomb
        enqueue-state!
        (event "ctx-sb" :scatter-bomb/fx-beam {:mode :beam
                                                 :start {:x 1.0 :y 64.0 :z 2.0}
                                                 :end {:x 2.0 :y 64.0 :z 3.0}
                                                 :source-player-id "player-a"}))
      (level-effects/update-effect-state! :scatter-bomb
        enqueue-state!
        (event "ctx-sb" :scatter-bomb/fx-end {:mode :end :source-player-id "player-a"}))
      (is (nil? (get-in (sb-fx/scatter-bomb-fx-snapshot) [:effect-state [:ctx "ctx-sb"]])))
      (is (seq @particles*))
      (is (seq @sounds*)))))

(deftest scatter-bomb-tick-cadence-test
  (let [enqueue-state! (var-get #'cn.li.ac.content.ability.meltdowner.scatter-bomb-fx/enqueue-state!)
        tick-state! (var-get #'cn.li.ac.content.ability.meltdowner.scatter-bomb-fx/tick-state!)]
    (with-redefs [client-particles/queue-current-particle-effect! (fn [& _] nil)
                  client-sounds/queue-current-sound-effect! (fn [& _] nil)]
      (level-effects/update-effect-state! :scatter-bomb
        enqueue-state!
        (event "ctx-cadence" :scatter-bomb/fx-start {:mode :start :source-player-id "player-a"}))
      (level-effects/update-effect-state! :scatter-bomb
        enqueue-state!
        (event "ctx-cadence" :scatter-bomb/fx-ball {:mode :ball
                                                     :x 1.0 :y 64.0 :z 2.0
                                                     :count 4
                                                     :source-player-id "player-a"}))

      (dotimes [_ 5]
        (level-effects/update-effect-state! :scatter-bomb
          (fn [store _]
            (tick-state! store))
          nil))

      (is (= 5 (get-in (sb-fx/scatter-bomb-fx-snapshot) [:effect-state [:ctx "ctx-cadence"] :ticks])))
      (is (= 4 (get-in (sb-fx/scatter-bomb-fx-snapshot) [:effect-state [:ctx "ctx-cadence"] :balls])))

      (level-effects/update-effect-state! :scatter-bomb
        enqueue-state!
        (event "ctx-cadence" :scatter-bomb/fx-end {:mode :end :source-player-id "player-a"}))
      (is (nil? (get-in (sb-fx/scatter-bomb-fx-snapshot) [:effect-state [:ctx "ctx-cadence"]]))))))



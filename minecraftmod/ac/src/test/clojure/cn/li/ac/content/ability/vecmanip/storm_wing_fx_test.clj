(ns cn.li.ac.content.ability.vecmanip.storm-wing-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.content.ability.vecmanip.storm-wing-fx :as swfx]))

(defn- reset-fixture [f]
  (try
        (level-effects/reset-level-effect-registry-for-test!)
        (swfx/reset-storm-wing-fx-for-test!)
        (f)
        (finally
          (swfx/reset-storm-wing-fx-for-test!)
          (level-effects/reset-level-effect-registry-for-test!))))

(use-fixtures :each reset-fixture)

;; StormWing has no arc-beam impl — it owns its enqueue/tick fns and registers
;; them through fx-spec, so tests drive those directly.
(defn- enqueue!
  [enqueue-state! ctx-id payload]
  (level-effects/update-effect-state! :storm-wing
    (fn [store]
      (enqueue-state! store ctx-id :storm-wing/fx-update [:ctx ctx-id] payload)))
  nil)

(defn- tick!
  [tick-state!]
  (level-effects/update-effect-state! :storm-wing
    (fn [store] (tick-state! store)))
  nil)

(deftest init-registers-owner-aware-storm-wing-fx-test
  (let [registered-level* (atom nil)
        registered-topics* (atom #{})]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                         (reset! registered-level* [effect-id effect-map])
                                                         nil)
                  fx-registry/register-fx-channel! (fn [topic _handler]
                                                      (swap! registered-topics* conj topic)
                                                      nil)]
      (swfx/init!)
      (is (= :storm-wing (first @registered-level*)))
      (is (fn? (:enqueue-state-fn (second @registered-level*))))
      (is (= #{:storm-wing/fx-start
               :storm-wing/fx-update
               :storm-wing/fx-end}
             @registered-topics*)))))

(deftest flying-build-plan-queues-particles-and-loop-sound-test
  (let [enqueue-state! (var-get #'cn.li.ac.content.ability.vecmanip.storm-wing-fx/enqueue-state!)
        tick-state! (var-get #'cn.li.ac.content.ability.vecmanip.storm-wing-fx/tick-state!)
        build-plan (var-get #'cn.li.ac.content.ability.vecmanip.storm-wing-fx/build-plan)
        sound-calls* (atom [])
        particle-calls* (atom [])]
    (with-redefs [client-particles/current-effect-owner (fn [] {:client-session-id "storm-wing-test"})
                  client-sounds/queue-sound-effect! (fn [& args]
                                                      (swap! sound-calls* conj args)
                                                      nil)
                  client-particles/queue-particle-effect! (fn [& args]
                                                            (swap! particle-calls* conj args)
                                                            nil)
                  rand (fn [] 0.5)]
      (enqueue! enqueue-state! "ctx-main" {:mode :start :source-player-id "player-a"})
      (enqueue! enqueue-state! "ctx-main" {:mode :update :phase :flying :charge-ticks 40 :charge-ratio 1.0 :source-player-id "player-a"})
      (dotimes [_ 10]
        (tick! tick-state!))
      (let [plan (build-plan nil {:x 0.0 :y 64.0 :z 0.0 :player-uuid "player-a"} 0 nil)]
        (is (= 2 (count @sound-calls*)))
        (is (= 12 (count @particle-calls*)))
        (is (= 40 (count (:ops plan))))
        (is (= 10 (get-in (swfx/storm-wing-fx-snapshot)
                          [:effect-state [:ctx "ctx-main"] :ticks])))))))



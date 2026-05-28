(ns cn.li.ac.content.ability.vecmanip.storm-wing-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.content.ability.vecmanip.storm-wing-fx :as swfx]))

(defn- with-fresh-storm-wing-fx-runtime [f]
  (swfx/call-with-storm-wing-fx-runtime
    (swfx/create-storm-wing-fx-runtime)
    (fn []
      (try
        (f)
        (finally
          (swfx/reset-storm-wing-fx-for-test!))))))

(defn- event [ctx-id payload]
  {:payload payload
   :ctx-id ctx-id
   :channel :storm-wing/fx-update
   :owner-key [:ctx ctx-id]})

(use-fixtures :each with-fresh-storm-wing-fx-runtime)

(deftest init-registers-storm-wing-fx-channels-test
  (let [registered-effect (atom nil)
        registered-handler (atom nil)]
    (with-redefs [level-effects/register-level-effect!
                  (fn [effect-id effect-map]
                    (reset! registered-effect [effect-id effect-map])
                    nil)
                  fx-registry/register-fx-channels!
                  (fn [channel-keys handler-fn]
                    (reset! registered-handler {:channels channel-keys
                                                :handler handler-fn})
                    nil)]
      (swfx/init!)
      (is (= :storm-wing (first @registered-effect)))
      (is (= #{:storm-wing/fx-start
               :storm-wing/fx-update
               :storm-wing/fx-end}
             (set (:channels @registered-handler)))))))

(deftest fx-handler-routes-start-update-end-payloads-test
  (let [registered-handler (atom nil)
        enqueued (atom [])]
    (with-redefs [level-effects/register-level-effect! (fn [& _] nil)
                  fx-registry/register-fx-channels!
                  (fn [_ handler-fn]
                    (reset! registered-handler handler-fn)
                    nil)
                  level-effects/enqueue-level-effect!
                  (fn [effect-id payload fx-context]
                    (swap! enqueued conj [effect-id payload fx-context])
                    nil)]
      (swfx/init!)
      (@registered-handler "ctx-1" :storm-wing/fx-start {:charge-ticks 70})
      (@registered-handler "ctx-1" :storm-wing/fx-update {:phase :flying :charge-ticks 40 :charge-ratio 1.0})
      (@registered-handler "ctx-1" :storm-wing/fx-end nil)
      (is (= [[:storm-wing {:mode :start :charge-ticks 70}
               {:ctx-id "ctx-1" :channel :storm-wing/fx-start}]
              [:storm-wing {:mode :update
                            :phase :flying
                            :charge-ticks 40
                            :charge-ratio 1.0}
               {:ctx-id "ctx-1" :channel :storm-wing/fx-update}]
              [:storm-wing {:mode :end}
               {:ctx-id "ctx-1" :channel :storm-wing/fx-end}]]
             @enqueued)))))

(deftest flying-build-plan-queues-particles-and-loop-sound-test
  (let [enqueue! @#'cn.li.ac.content.ability.vecmanip.storm-wing-fx/enqueue!
        tick! @#'cn.li.ac.content.ability.vecmanip.storm-wing-fx/tick!
        build-plan @#'cn.li.ac.content.ability.vecmanip.storm-wing-fx/build-plan
        sound-calls (atom [])
        particle-calls (atom [])]
    (with-redefs [client-particles/current-effect-owner (fn [] {:client-session-id "storm-wing-test"})
                  client-sounds/queue-sound-effect! (fn [& args]
                                                      (swap! sound-calls conj args)
                                                      nil)
                  client-particles/queue-particle-effect! (fn [& args]
                                                            (swap! particle-calls conj args)
                                                            nil)
                  rand (fn [] 0.5)]
      (enqueue! (event "ctx-main" {:mode :start :charge-ticks 70}))
      (enqueue! (event "ctx-main" {:mode :update :phase :flying :charge-ticks 40 :charge-ratio 1.0}))
      (dotimes [_ 10] (tick!))
      (let [plan (build-plan nil {:x 0.0 :y 64.0 :z 0.0 :player-uuid nil} 0)]
        (is (= 2 (count @sound-calls)))
        (is (= 12 (count @particle-calls)))
        (is (= 40 (count (:ops plan))))
        (is (= 10 (get-in (swfx/storm-wing-fx-snapshot)
                          [:effect-state [:ctx "ctx-main"] :ticks])))))))

(deftest storm-wing-fx-runtime-isolation-test
  (let [runtime-a (swfx/create-storm-wing-fx-runtime)
        runtime-b (swfx/create-storm-wing-fx-runtime)
        enqueue! @#'cn.li.ac.content.ability.vecmanip.storm-wing-fx/enqueue!]
    (with-redefs [client-particles/current-effect-owner (fn [] {:client-session-id "storm-wing-test"})
                  client-sounds/queue-sound-effect! (fn [& _] nil)]
      (swfx/call-with-storm-wing-fx-runtime
        runtime-a
        (fn []
          (enqueue! (event "ctx-a" {:mode :start :charge-ticks 70}))
          (is (= :charging (get-in (swfx/storm-wing-fx-snapshot)
                                   [:effect-state [:ctx "ctx-a"] :phase])))))
      (swfx/call-with-storm-wing-fx-runtime
        runtime-b
        (fn []
          (is (= {:effect-state {}}
                 (swfx/storm-wing-fx-snapshot)))
          (enqueue! (event "ctx-b" {:mode :start :charge-ticks 40}))
          (is (= #{[:ctx "ctx-b"]}
                 (set (keys (:effect-state (swfx/storm-wing-fx-snapshot))))))))
      (swfx/call-with-storm-wing-fx-runtime
        runtime-a
        (fn []
          (is (= :charging (get-in (swfx/storm-wing-fx-snapshot)
                                   [:effect-state [:ctx "ctx-a"] :phase]))))))))

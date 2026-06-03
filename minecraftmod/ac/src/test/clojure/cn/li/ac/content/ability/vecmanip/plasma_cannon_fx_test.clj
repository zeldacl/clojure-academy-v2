(ns cn.li.ac.content.ability.vecmanip.plasma-cannon-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.content.ability.vecmanip.plasma-cannon-fx :as pcfx]))

(defn- reset-fixture [f]
  (level-effects/call-with-level-effect-runtime
    (level-effects/create-level-effect-runtime)
    (fn []
      (try
        (level-effects/reset-level-effect-registry-for-test!)
        (pcfx/reset-plasma-cannon-fx-for-test!)
        (f)
        (finally
          (pcfx/reset-plasma-cannon-fx-for-test!)
          (level-effects/reset-level-effect-registry-for-test!))))))

(use-fixtures :each reset-fixture)

(defn- event
  [ctx-id payload]
  {:payload payload
   :ctx-id ctx-id
   :channel :plasma-cannon/fx-update
   :owner-key [:ctx ctx-id]})

(deftest fx-handler-routes-start-update-perform-end-payloads-test
  (let [registered-handler* (atom nil)
        enqueued* (atom [])]
    (with-redefs [level-effects/register-level-effect! (fn [& _] nil)
                  fx-registry/register-fx-channels! (fn [_ handler]
                                                      (reset! registered-handler* handler)
                                                      nil)
                  level-effects/enqueue-level-effect! (fn [effect-id payload fx-context]
                                                        (swap! enqueued* conj [effect-id payload fx-context])
                                                        nil)]
      (pcfx/init!)
      (@registered-handler* "ctx-1" :plasma-cannon/fx-start {:charge-pos {:x 1.0 :y 64.0 :z 1.0}})
      (@registered-handler* "ctx-1" :plasma-cannon/fx-update {:charge-ticks 24
                                                                :fully-charged? true
                                                                :charge-pos {:x 1.0 :y 64.0 :z 1.0}
                                                                :flight-ticks 2
                                                                :state :go
                                                                :destination {:x 4.0 :y 64.0 :z 4.0}})
      (@registered-handler* "ctx-1" :plasma-cannon/fx-perform {:pos {:x 2.0 :y 65.0 :z 2.0}})
      (@registered-handler* "ctx-1" :plasma-cannon/fx-end {:performed? true})
      (is (= [[:plasma-cannon {:mode :start
                               :charge-pos {:x 1.0 :y 64.0 :z 1.0}}
               {:ctx-id "ctx-1" :channel :plasma-cannon/fx-start}]
              [:plasma-cannon {:mode :update
                               :charge-ticks 24
                               :fully-charged? true
                               :charge-pos {:x 1.0 :y 64.0 :z 1.0}
                               :flight-ticks 2
                               :state :go
                               :destination {:x 4.0 :y 64.0 :z 4.0}}
               {:ctx-id "ctx-1" :channel :plasma-cannon/fx-update}]
              [:plasma-cannon {:mode :perform
                               :pos {:x 2.0 :y 65.0 :z 2.0}}
               {:ctx-id "ctx-1" :channel :plasma-cannon/fx-perform}]
              [:plasma-cannon {:mode :end
                               :performed? true}
               {:ctx-id "ctx-1" :channel :plasma-cannon/fx-end}]]
             @enqueued*)))))

(deftest tick-build-plan-and-perform-effects-test
  (let [enqueue-state! (var-get #'cn.li.ac.content.ability.vecmanip.plasma-cannon-fx/enqueue-state!)
        tick-state! (var-get #'cn.li.ac.content.ability.vecmanip.plasma-cannon-fx/tick-state!)
        build-plan (var-get #'cn.li.ac.content.ability.vecmanip.plasma-cannon-fx/build-plan)
        sound-calls* (atom [])
        particle-calls* (atom [])]
    (with-redefs [client-particles/current-effect-owner (fn [] {:client-session-id "plasma-cannon-test"})
                  client-sounds/queue-sound-effect! (fn [& args]
                                                      (swap! sound-calls* conj args)
                                                      nil)
                  client-particles/queue-particle-effect! (fn [& args]
                                                            (swap! particle-calls* conj args)
                                                            nil)
                  rand (fn [] 0.5)]
      (level-effects/update-effect-state! :plasma-cannon
        enqueue-state!
        (event "ctx-main" {:mode :start :charge-pos {:x 1.0 :y 64.0 :z 1.0}}))
      (level-effects/update-effect-state! :plasma-cannon
        enqueue-state!
        (event "ctx-main" {:mode :update
                             :charge-ticks 24
                             :fully-charged? true
                             :charge-pos {:x 1.0 :y 64.0 :z 1.0}
                             :flight-ticks 2
                             :state :go
                             :destination {:x 4.0 :y 64.0 :z 4.0}}))
      (dotimes [_ 10]
        (level-effects/update-effect-state! :plasma-cannon
          (fn [store _]
            (tick-state! store))
          nil))
      (let [plan (build-plan nil nil 0)]
        (is (= 3 (count @sound-calls*)))
        (is (= 10 (count @particle-calls*)))
        (is (= 1 (count (:ops plan))))
        (is (= 10 (get-in (pcfx/plasma-cannon-fx-snapshot)
                          [:effect-state [:ctx "ctx-main"] :ticks]))))
      (reset! sound-calls* [])
      (reset! particle-calls* [])
      (level-effects/update-effect-state! :plasma-cannon
        enqueue-state!
        (event "ctx-main" {:mode :perform :pos {:x 2.0 :y 65.0 :z 2.0}}))
      (is (= 1 (count @sound-calls*)))
      (is (= 13 (count @particle-calls*))))))

(deftest plasma-cannon-fx-runtime-isolation-test
  (let [runtime-a (level-effects/create-level-effect-runtime)
        runtime-b (level-effects/create-level-effect-runtime)
        enqueue-state! (var-get #'cn.li.ac.content.ability.vecmanip.plasma-cannon-fx/enqueue-state!)]
    (with-redefs [client-particles/current-effect-owner (fn [] {:client-session-id "plasma-cannon-test"})
                  client-sounds/queue-sound-effect! (fn [& _] nil)
                  client-particles/queue-particle-effect! (fn [& _] nil)]
      (level-effects/call-with-level-effect-runtime
        runtime-a
        (fn []
          (level-effects/update-effect-state! :plasma-cannon
            enqueue-state!
            (event "ctx-a" {:mode :start :charge-pos {:x 1.0 :y 64.0 :z 1.0}}))
          (is (= #{[:ctx "ctx-a"]}
                 (set (keys (:effect-state (pcfx/plasma-cannon-fx-snapshot))))))))
      (level-effects/call-with-level-effect-runtime
        runtime-b
        (fn []
          (is (= {:effect-state {}}
                 (pcfx/plasma-cannon-fx-snapshot)))
          (level-effects/update-effect-state! :plasma-cannon
            enqueue-state!
            (event "ctx-b" {:mode :start :charge-pos {:x 2.0 :y 64.0 :z 2.0}}))
          (is (= #{[:ctx "ctx-b"]}
                 (set (keys (:effect-state (pcfx/plasma-cannon-fx-snapshot))))))))
      (level-effects/call-with-level-effect-runtime
        runtime-a
        (fn []
          (is (= #{[:ctx "ctx-a"]}
                 (set (keys (:effect-state (pcfx/plasma-cannon-fx-snapshot)))))))))))

(ns cn.li.ac.content.ability.vecmanip.plasma-cannon-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.content.ability.vecmanip.plasma-cannon-fx :as pcfx]))

(defn- with-fresh-plasma-cannon-fx-runtime [f]
  (pcfx/call-with-plasma-cannon-fx-runtime
    (pcfx/create-plasma-cannon-fx-runtime)
    (fn []
      (try
        (f)
        (finally
          (pcfx/reset-plasma-cannon-fx-for-test!))))))

(defn- event [ctx-id payload]
  {:payload payload
   :ctx-id ctx-id
   :channel :plasma-cannon/fx-update
   :owner-key [:ctx ctx-id]})

(use-fixtures :each with-fresh-plasma-cannon-fx-runtime)

(deftest init-registers-plasma-cannon-fx-channels-test
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
      (pcfx/init!)
      (is (= :plasma-cannon (first @registered-effect)))
      (is (= #{:plasma-cannon/fx-start
               :plasma-cannon/fx-update
               :plasma-cannon/fx-perform
               :plasma-cannon/fx-end}
             (set (:channels @registered-handler)))))))

(deftest fx-handler-routes-start-update-perform-end-payloads-test
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
      (pcfx/init!)
      (@registered-handler "ctx-1" :plasma-cannon/fx-start {:charge-pos {:x 1.0 :y 64.0 :z 1.0}})
      (@registered-handler "ctx-1" :plasma-cannon/fx-update {:charge-ticks 24
                          :fully-charged? true
                                                              :charge-pos {:x 1.0 :y 64.0 :z 1.0}
                                                              :flight-ticks 2
                                                              :state :go
                                                              :destination {:x 4.0 :y 64.0 :z 4.0}})
      (@registered-handler "ctx-1" :plasma-cannon/fx-perform {:pos {:x 2.0 :y 65.0 :z 2.0}})
      (@registered-handler "ctx-1" :plasma-cannon/fx-end {:performed? true})
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
             @enqueued)))))

(deftest tick-build-plan-and-perform-effects-test
  (let [enqueue! @#'cn.li.ac.content.ability.vecmanip.plasma-cannon-fx/enqueue!
        tick! @#'cn.li.ac.content.ability.vecmanip.plasma-cannon-fx/tick!
        build-plan @#'cn.li.ac.content.ability.vecmanip.plasma-cannon-fx/build-plan
        sound-calls (atom [])
        particle-calls (atom [])]
    (with-redefs [client-particles/current-effect-owner (fn [] {:client-session-id "plasma-cannon-test"})
                  client-sounds/queue-sound-effect! (fn [& args]
                                                      (swap! sound-calls conj args)
                                                      nil)
                  client-particles/queue-particle-effect! (fn [& args]
                                                            (swap! particle-calls conj args)
                                                            nil)
                  rand (fn [] 0.5)]
      (enqueue! (event "ctx-main" {:mode :start :charge-pos {:x 1.0 :y 64.0 :z 1.0}}))
      (enqueue! (event "ctx-main" {:mode :update
                                    :charge-ticks 24
                                    :fully-charged? true
                                    :charge-pos {:x 1.0 :y 64.0 :z 1.0}
                                    :flight-ticks 2
                                    :state :go
                                    :destination {:x 4.0 :y 64.0 :z 4.0}}))
      (dotimes [_ 10] (tick!))
      (let [plan (build-plan nil nil 0)]
        (is (= 3 (count @sound-calls)))
        (is (= 10 (count @particle-calls)))
        (is (= 1 (count (:ops plan))))
        (is (= 10 (get-in (pcfx/plasma-cannon-fx-snapshot)
                          [:effect-state [:ctx "ctx-main"] :ticks]))))
      (reset! sound-calls [])
      (reset! particle-calls [])
      (enqueue! (event "ctx-main" {:mode :perform :pos {:x 2.0 :y 65.0 :z 2.0}}))
      (is (= 1 (count @sound-calls)))
      (is (= 13 (count @particle-calls))))))

(deftest plasma-cannon-fx-runtime-isolation-test
  (let [runtime-a (pcfx/create-plasma-cannon-fx-runtime)
        runtime-b (pcfx/create-plasma-cannon-fx-runtime)
        enqueue! @#'cn.li.ac.content.ability.vecmanip.plasma-cannon-fx/enqueue!]
    (with-redefs [client-particles/current-effect-owner (fn [] {:client-session-id "plasma-cannon-test"})
                  client-sounds/queue-sound-effect! (fn [& _] nil)
                  client-particles/queue-particle-effect! (fn [& _] nil)]
      (pcfx/call-with-plasma-cannon-fx-runtime
        runtime-a
        (fn []
          (enqueue! (event "ctx-a" {:mode :start :charge-pos {:x 1.0 :y 64.0 :z 1.0}}))
          (is (= #{[:ctx "ctx-a"]}
                 (set (keys (:effect-state (pcfx/plasma-cannon-fx-snapshot))))))))
      (pcfx/call-with-plasma-cannon-fx-runtime
        runtime-b
        (fn []
          (is (= {:effect-state {}}
                 (pcfx/plasma-cannon-fx-snapshot)))
          (enqueue! (event "ctx-b" {:mode :start :charge-pos {:x 2.0 :y 64.0 :z 2.0}}))
          (is (= #{[:ctx "ctx-b"]}
                 (set (keys (:effect-state (pcfx/plasma-cannon-fx-snapshot))))))))
      (pcfx/call-with-plasma-cannon-fx-runtime
        runtime-a
        (fn []
          (is (= #{[:ctx "ctx-a"]}
                 (set (keys (:effect-state (pcfx/plasma-cannon-fx-snapshot)))))))))))

(deftest plasma-cannon-fx-runtime-required-without-binding-test
  (binding [pcfx/*plasma-cannon-fx-runtime* nil]
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"runtime is not bound"
          (pcfx/plasma-cannon-fx-snapshot)))))

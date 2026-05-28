(ns cn.li.ac.content.ability.vecmanip.blood-retrograde-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.content.ability.vecmanip.blood-retrograde-fx :as brfx]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(defn- with-fresh-blood-retrograde-fx-runtime [f]
  (brfx/call-with-blood-retrograde-fx-runtime
    (brfx/create-blood-retrograde-fx-runtime)
    (fn []
      (try
        (f)
        (finally
          (brfx/reset-blood-retrograde-fx-for-test!))))))

(defn- event [ctx-id payload]
  {:payload payload
   :ctx-id ctx-id
   :channel :blood-retrograde/fx-perform
   :owner-key [:ctx ctx-id]})

(use-fixtures :each with-fresh-blood-retrograde-fx-runtime)

(deftest init-registers-blood-retrograde-fx-channels-test
  (let [registered-effect (atom nil)
        registered-handler (atom nil)]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                         (reset! registered-effect [effect-id effect-map])
                                                         nil)
                  fx-registry/register-fx-channels! (fn [channels handler]
                                                      (reset! registered-handler {:channels channels
                                                                                  :handler handler})
                                                      nil)]
      (brfx/init!)
      (is (= :blood-retrograde (first @registered-effect)))
      (is (= #{:blood-retrograde/fx-start
               :blood-retrograde/fx-update
               :blood-retrograde/fx-end
               :blood-retrograde/fx-perform}
             (set (:channels @registered-handler)))))))

(deftest fx-handler-routes-start-update-perform-end-test
  (let [handler* (atom nil)
        enqueue-fn* (atom nil)
        enqueued-effects* (atom [])
        sound-calls* (atom [])]
    (with-redefs [level-effects/register-level-effect! (fn [_effect-id effect-map]
                                                         (reset! enqueue-fn* (:enqueue-event-fn effect-map))
                                                         nil)
                  fx-registry/register-fx-channels! (fn [_ handler]
                                                      (reset! handler* handler)
                                                      nil)
                  level-effects/enqueue-level-effect! (fn [effect-id payload fx-context]
                                                        (swap! enqueued-effects* conj [effect-id payload fx-context])
                                                        (@enqueue-fn* {:payload payload
                                                                       :ctx-id (:ctx-id fx-context)
                                                                       :channel (:channel fx-context)
                                                                       :owner-key [:ctx (:ctx-id fx-context)]})
                                                        nil)
                  client-sounds/queue-current-sound-effect! (fn [payload]
                                                              (swap! sound-calls* conj payload)
                                                              nil)]
      (brfx/init!)
      (@handler* "ctx-1" :blood-retrograde/fx-start nil)
      (@handler* "ctx-1" :blood-retrograde/fx-update {:ticks 7 :charge-ratio 0.35})
      (@handler* "ctx-1" :blood-retrograde/fx-perform {:sound-pos {:x 1.0 :y 2.0 :z 3.0}
                                                       :splashes [{:x 1.0 :y 2.0 :z 3.0 :size 1.4}]
                                                       :sprays [{:x 4.0 :y 5.0 :z 6.0 :face :up :size 1.2 :rotation 0.0
                                                                :offset-u 0.0 :offset-v 0.0 :texture-id 1}]})
      (@handler* "ctx-1" :blood-retrograde/fx-end {:performed? true})
      (is (= [[:blood-retrograde {:mode :start}
           {:ctx-id "ctx-1" :channel :blood-retrograde/fx-start}]
          [:blood-retrograde {:mode :update :ticks 7 :charge-ratio 0.35}
           {:ctx-id "ctx-1" :channel :blood-retrograde/fx-update}]
              [:blood-retrograde {:mode :perform
                                  :sound-pos {:x 1.0 :y 2.0 :z 3.0}
                                  :splashes [{:x 1.0 :y 2.0 :z 3.0 :size 1.4}]
                                  :sprays [{:x 4.0 :y 5.0 :z 6.0 :face :up :size 1.2 :rotation 0.0
                    :offset-u 0.0 :offset-v 0.0 :texture-id 1}]}
           {:ctx-id "ctx-1" :channel :blood-retrograde/fx-perform}]
          [:blood-retrograde {:mode :end :performed? true}
           {:ctx-id "ctx-1" :channel :blood-retrograde/fx-end}]]
             @enqueued-effects*))
      (is (= 1 (count @sound-calls*)))
      (is (= "my_mod:vecmanip.blood_retro" (:sound-id (first @sound-calls*)))))))

(defn- approx= [expected actual]
  (< (Math/abs (- (double expected) (double actual))) 1.0e-6))

(deftest walk-speed-curve-and-tick-cleanup-test
  (let [build-plan @#'cn.li.ac.content.ability.vecmanip.blood-retrograde-fx/build-plan
        tick! @#'cn.li.ac.content.ability.vecmanip.blood-retrograde-fx/tick!
        enqueue! @#'cn.li.ac.content.ability.vecmanip.blood-retrograde-fx/enqueue!]
    (with-redefs [client-sounds/queue-current-sound-effect! (fn [_] nil)]
      (enqueue! (event "ctx-main" {:mode :start}))
        (is (approx= 0.1 (:local-walk-speed (build-plan {:x 0.0 :y 0.0 :z 0.0}
                    {:x 0.0 :y 0.0 :z 0.0}
                    0))))
      (enqueue! (event "ctx-main" {:mode :update :ticks 10 :charge-ratio 0.5}))
        (is (approx= 0.0535 (:local-walk-speed (build-plan {:x 0.0 :y 0.0 :z 0.0}
                       {:x 0.0 :y 0.0 :z 0.0}
                       10))))
      (enqueue! (event "ctx-main" {:mode :update :ticks 20 :charge-ratio 1.0}))
        (is (approx= 0.007 (:local-walk-speed (build-plan {:x 0.0 :y 0.0 :z 0.0}
                      {:x 0.0 :y 0.0 :z 0.0}
                      20))))
      (enqueue! (event "ctx-main"
                       {:mode :perform
                        :sound-pos {:x 0.0 :y 0.0 :z 0.0}
                        :splashes [{:x 1.0 :y 2.0 :z 3.0 :size 1.0}]
                        :sprays [{:x 4.0 :y 5.0 :z 6.0 :face :up :size 1.0 :rotation 0.0
                                  :offset-u 0.0 :offset-v 0.0 :texture-id 1}]}))
      (tick!)
      (is (= 21 (:ticks (get (:effect-state (brfx/blood-retrograde-fx-snapshot)) [:ctx "ctx-main"]))))
      (dotimes [_ 9] (tick!))
      (let [snapshot (brfx/blood-retrograde-fx-snapshot)]
        (is (nil? (get (:splashes snapshot) [:ctx "ctx-main"])))
        (is (= 1 (count (get (:sprays snapshot) [:ctx "ctx-main"]))))))))

(deftest two-owners-keep-blood-retrograde-state-and-queues-independent-test
  (let [enqueue! @#'cn.li.ac.content.ability.vecmanip.blood-retrograde-fx/enqueue!]
    (with-redefs [client-sounds/queue-current-sound-effect! (fn [_] nil)]
      (enqueue! (event "ctx-a" {:mode :start}))
      (enqueue! (event "ctx-b" {:mode :start}))
      (enqueue! (event "ctx-a" {:mode :update :ticks 5 :charge-ratio 0.25}))
      (enqueue! (event "ctx-b" {:mode :update :ticks 15 :charge-ratio 0.75}))
      (enqueue! (event "ctx-a"
                       {:mode :perform
                        :sound-pos {:x 1.0 :y 2.0 :z 3.0}
                        :splashes [{:x 1.0 :y 2.0 :z 3.0 :size 1.0}]
                        :sprays [{:x 4.0 :y 5.0 :z 6.0 :face :up :size 1.0}]}))
      (enqueue! (event "ctx-b"
                       {:mode :perform
                        :sound-pos {:x 2.0 :y 3.0 :z 4.0}
                        :splashes [{:x 2.0 :y 3.0 :z 4.0 :size 1.0}]
                        :sprays [{:x 5.0 :y 6.0 :z 7.0 :face :north :size 1.0}]}))
      (let [snapshot (brfx/blood-retrograde-fx-snapshot)]
        (is (= 5 (:ticks (get (:effect-state snapshot) [:ctx "ctx-a"]))))
        (is (= 15 (:ticks (get (:effect-state snapshot) [:ctx "ctx-b"]))))
        (is (= 1 (count (get (:splashes snapshot) [:ctx "ctx-a"]))))
        (is (= 1 (count (get (:sprays snapshot) [:ctx "ctx-b"]))))
        (brfx/clear-blood-retrograde-owner! [:ctx "ctx-a"])
        (let [after-clear (brfx/blood-retrograde-fx-snapshot)]
          (is (nil? (get (:effect-state after-clear) [:ctx "ctx-a"])))
          (is (nil? (get (:splashes after-clear) [:ctx "ctx-a"])))
          (is (= 1 (count (get (:sprays after-clear) [:ctx "ctx-b"])))))))))

(deftest blood-retrograde-fx-runtime-isolation-test
  (let [runtime-a (brfx/create-blood-retrograde-fx-runtime)
        runtime-b (brfx/create-blood-retrograde-fx-runtime)
        enqueue! @#'cn.li.ac.content.ability.vecmanip.blood-retrograde-fx/enqueue!]
    (with-redefs [client-sounds/queue-current-sound-effect! (fn [_] nil)]
      (brfx/call-with-blood-retrograde-fx-runtime
        runtime-a
        (fn []
          (enqueue! (event "ctx-a" {:mode :start}))
          (enqueue! (event "ctx-a"
                           {:mode :perform
                            :sound-pos {:x 1.0 :y 2.0 :z 3.0}
                            :splashes [{:x 1.0 :y 2.0 :z 3.0 :size 1.0}]
                            :sprays [{:x 4.0 :y 5.0 :z 6.0 :face :up :size 1.0}]}))
          (is (= 1 (count (get (:splashes (brfx/blood-retrograde-fx-snapshot)) [:ctx "ctx-a"]))))))
      (brfx/call-with-blood-retrograde-fx-runtime
        runtime-b
        (fn []
          (is (= {:effect-state {}
                  :splashes {}
                  :sprays {}}
                 (brfx/blood-retrograde-fx-snapshot)))
          (enqueue! (event "ctx-b" {:mode :start}))
          (is (= #{[:ctx "ctx-b"]}
                 (set (keys (:effect-state (brfx/blood-retrograde-fx-snapshot))))))))
      (brfx/call-with-blood-retrograde-fx-runtime
        runtime-a
        (fn []
          (is (= 1 (count (get (:splashes (brfx/blood-retrograde-fx-snapshot)) [:ctx "ctx-a"])))))))))

(ns cn.li.ac.content.ability.vecmanip.directed-blastwave-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.content.ability.vecmanip.directed-blastwave-fx :as blastwave-fx]))

(defn- with-fresh-directed-blastwave-fx-runtime [f]
  (blastwave-fx/call-with-directed-blastwave-fx-runtime
    (blastwave-fx/create-directed-blastwave-fx-runtime)
    (fn []
      (try
        (f)
        (finally
          (blastwave-fx/reset-directed-blastwave-fx-for-test!))))))

(defn- event [ctx-id payload]
  {:payload payload
   :ctx-id ctx-id
   :channel :directed-blastwave/fx-perform
   :owner-key [:ctx ctx-id]})

(use-fixtures :each with-fresh-directed-blastwave-fx-runtime)

(deftest init-registers-directed-blastwave-fx-channels-test
  (let [registered-level* (atom nil)
        registered-handler* (atom nil)]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                         (reset! registered-level* [effect-id effect-map])
                                                         nil)
                  fx-registry/register-fx-channels! (fn [channels handler]
                                                      (reset! registered-handler* {:channels channels
                                                                                   :handler handler})
                                                      nil)]
      (blastwave-fx/init!)
      (is (= :directed-blastwave (first @registered-level*)))
      (is (= #{:directed-blastwave/fx-start
               :directed-blastwave/fx-update
               :directed-blastwave/fx-perform
               :directed-blastwave/fx-end}
             (set (:channels @registered-handler*)))))))

(deftest fx-handler-routes-start-update-perform-end-payloads-test
  (let [handler* (atom nil)
        level-enqueued* (atom [])]
    (with-redefs [level-effects/register-level-effect! (fn [& _] nil)
                  fx-registry/register-fx-channels! (fn [_ handler]
                                                      (reset! handler* handler)
                                                      nil)
                  level-effects/enqueue-level-effect! (fn [effect-id payload fx-context]
                                                        (swap! level-enqueued* conj [effect-id payload fx-context])
                                                        nil)]
      (blastwave-fx/init!)
      (@handler* "ctx-1" :directed-blastwave/fx-start nil)
      (@handler* "ctx-1" :directed-blastwave/fx-update {:charge-ticks 11 :punched? true})
      (@handler* "ctx-1" :directed-blastwave/fx-perform {:pos {:x 1.0 :y 2.0 :z 3.0}
                                                          :look-dir {:x 0.0 :y 0.0 :z 1.0}
                                                          :charge-ticks 17})
      (@handler* "ctx-1" :directed-blastwave/fx-end {:performed? false})
      (is (= [[:directed-blastwave {:mode :start}
           {:ctx-id "ctx-1" :channel :directed-blastwave/fx-start}]
          [:directed-blastwave {:mode :update :charge-ticks 11 :punched? true}
           {:ctx-id "ctx-1" :channel :directed-blastwave/fx-update}]
          [:directed-blastwave {:mode :perform
                :pos {:x 1.0 :y 2.0 :z 3.0}
                :look-dir {:x 0.0 :y 0.0 :z 1.0}
                :charge-ticks 17}
           {:ctx-id "ctx-1" :channel :directed-blastwave/fx-perform}]
          [:directed-blastwave {:mode :end :performed? false}
           {:ctx-id "ctx-1" :channel :directed-blastwave/fx-end}]]
             @level-enqueued*)))))

(deftest enqueue-perform-spawns-wave-and-queues-sound-test
  (let [enqueue! @#'cn.li.ac.content.ability.vecmanip.directed-blastwave-fx/enqueue!
        sound-calls* (atom [])]
    (with-redefs [client-sounds/queue-current-sound-effect! (fn [payload]
                                                              (swap! sound-calls* conj payload)
                                                              nil)
                  rand-int (fn [_] 0)
                  rand (fn [] 0.5)]
      (enqueue! (event "ctx-wave"
               {:mode :perform
            :pos {:x 1.0 :y 2.0 :z 3.0}
            :look-dir {:x 0.0 :y 0.0 :z 1.0}
            :charge-ticks 20}))
      (is (= 1 (count (get (:waves (blastwave-fx/directed-blastwave-fx-snapshot)) [:ctx "ctx-wave"]))))
      (is (= 1 (count @sound-calls*)))
      (is (= "my_mod:vecmanip.directed_blast"
             (:sound-id (first @sound-calls*)))))))

(deftest tick-clears-finished-state-and-expires-wave-test
  (let [enqueue! @#'cn.li.ac.content.ability.vecmanip.directed-blastwave-fx/enqueue!
        tick! @#'cn.li.ac.content.ability.vecmanip.directed-blastwave-fx/tick!]
    (with-redefs [client-sounds/queue-current-sound-effect! (fn [_] nil)]
      (enqueue! (event "ctx-tick" {:mode :start}))
      (enqueue! (event "ctx-tick" {:mode :end :performed? false}))
      (tick!)
      (is (empty? (:effect-state (blastwave-fx/directed-blastwave-fx-snapshot))))
      (enqueue! (event "ctx-tick"
                       {:mode :perform
                        :pos {:x 1.0 :y 2.0 :z 3.0}
                        :look-dir {:x 0.0 :y 0.0 :z 1.0}}))
      (dotimes [_ 15] (tick!))
      (is (empty? (:waves (blastwave-fx/directed-blastwave-fx-snapshot)))))))

(deftest two-owners-keep-blastwave-state-and-waves-independent-test
  (let [enqueue! @#'cn.li.ac.content.ability.vecmanip.directed-blastwave-fx/enqueue!
        tick! @#'cn.li.ac.content.ability.vecmanip.directed-blastwave-fx/tick!]
  (with-redefs [client-sounds/queue-current-sound-effect! (fn [_] nil)
                  rand-int (fn [_] 0)
                  rand (fn [] 0.5)]
      (enqueue! (event "ctx-a" {:mode :start}))
      (enqueue! (event "ctx-b" {:mode :start}))
      (enqueue! (event "ctx-a" {:mode :update :charge-ticks 7 :punched? true}))
      (enqueue! (event "ctx-b" {:mode :update :charge-ticks 11 :punched? false}))
      (let [snapshot (blastwave-fx/directed-blastwave-fx-snapshot)]
        (is (= 7 (get-in (get (:effect-state snapshot) [:ctx "ctx-a"]) [:charge-ticks])))
        (is (= 11 (get-in (get (:effect-state snapshot) [:ctx "ctx-b"]) [:charge-ticks]))))
      (enqueue! (event "ctx-a" {:mode :end :performed? false}))
      (tick!)
      (let [snapshot (blastwave-fx/directed-blastwave-fx-snapshot)]
        (is (nil? (get (:effect-state snapshot) [:ctx "ctx-a"])))
        (is (= 11 (get-in (get (:effect-state snapshot) [:ctx "ctx-b"]) [:charge-ticks]))))
      (enqueue! (event "ctx-a"
                       {:mode :perform
                        :pos {:x 1.0 :y 2.0 :z 3.0}
                        :look-dir {:x 0.0 :y 0.0 :z 1.0}}))
      (enqueue! (event "ctx-b"
                       {:mode :perform
                        :pos {:x 10.0 :y 2.0 :z 3.0}
                        :look-dir {:x 1.0 :y 0.0 :z 0.0}}))
      (let [snapshot (blastwave-fx/directed-blastwave-fx-snapshot)]
        (is (= #{[:ctx "ctx-a"] [:ctx "ctx-b"]}
               (set (keys (:waves snapshot)))))
        (is (= 1 (count (get (:waves snapshot) [:ctx "ctx-a"]))))
        (is (= 1 (count (get (:waves snapshot) [:ctx "ctx-b"]))))
        (blastwave-fx/clear-directed-blastwave-owner! [:ctx "ctx-a"])
        (let [after-clear (blastwave-fx/directed-blastwave-fx-snapshot)]
          (is (nil? (get (:waves after-clear) [:ctx "ctx-a"])))
          (is (= 1 (count (get (:waves after-clear) [:ctx "ctx-b"])))))))))

(deftest directed-blastwave-fx-runtime-isolation-test
  (let [runtime-a (blastwave-fx/create-directed-blastwave-fx-runtime)
        runtime-b (blastwave-fx/create-directed-blastwave-fx-runtime)
        enqueue! @#'cn.li.ac.content.ability.vecmanip.directed-blastwave-fx/enqueue!]
    (with-redefs [client-sounds/queue-current-sound-effect! (fn [_] nil)
                  rand-int (fn [_] 0)
                  rand (fn [] 0.5)]
      (blastwave-fx/call-with-directed-blastwave-fx-runtime
        runtime-a
        (fn []
          (enqueue! (event "ctx-a" {:mode :start}))
          (enqueue! (event "ctx-a" {:mode :update :charge-ticks 9 :punched? true}))
          (enqueue! (event "ctx-a"
                           {:mode :perform
                            :pos {:x 1.0 :y 2.0 :z 3.0}
                            :look-dir {:x 0.0 :y 0.0 :z 1.0}}))
          (is (= 9 (get-in (blastwave-fx/directed-blastwave-fx-snapshot)
                           [:effect-state [:ctx "ctx-a"] :charge-ticks])))
          (is (= 1 (count (get (:waves (blastwave-fx/directed-blastwave-fx-snapshot)) [:ctx "ctx-a"]))))))
      (blastwave-fx/call-with-directed-blastwave-fx-runtime
        runtime-b
        (fn []
          (is (= {:effect-state {}
                  :waves {}}
                 (blastwave-fx/directed-blastwave-fx-snapshot)))
          (enqueue! (event "ctx-b" {:mode :start}))
          (is (= #{[:ctx "ctx-b"]}
                 (set (keys (:effect-state (blastwave-fx/directed-blastwave-fx-snapshot))))))))
      (blastwave-fx/call-with-directed-blastwave-fx-runtime
        runtime-a
        (fn []
          (is (= 9 (get-in (blastwave-fx/directed-blastwave-fx-snapshot)
                           [:effect-state [:ctx "ctx-a"] :charge-ticks]))))))))

(deftest directed-blastwave-fx-runtime-required-without-binding-test
  (blastwave-fx/call-with-directed-blastwave-fx-runtime nil
    (fn []
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"runtime is not bound"
            (blastwave-fx/directed-blastwave-fx-snapshot))))))

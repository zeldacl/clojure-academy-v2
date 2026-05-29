(ns cn.li.ac.content.ability.vecmanip.directed-blastwave-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.content.ability.vecmanip.directed-blastwave-fx :as blastwave-fx]))

(defn- reset-fixture [f]
  (level-effects/call-with-level-effect-runtime
    (level-effects/create-level-effect-runtime)
    (fn []
      (try
        (level-effects/reset-level-effect-registry-for-test!)
        (blastwave-fx/reset-directed-blastwave-fx-for-test!)
        (f)
        (finally
          (blastwave-fx/reset-directed-blastwave-fx-for-test!)
          (level-effects/reset-level-effect-registry-for-test!))))))

(use-fixtures :each reset-fixture)

(defn- event
  [ctx-id payload]
  {:payload payload
   :ctx-id ctx-id
   :channel :directed-blastwave/fx-perform
   :owner-key [:ctx ctx-id]})

(deftest init-registers-owner-aware-directed-blastwave-fx-test
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
      (is (fn? (:enqueue-state-fn (second @registered-level*))))
      (is (= #{:directed-blastwave/fx-start
               :directed-blastwave/fx-update
               :directed-blastwave/fx-perform
               :directed-blastwave/fx-end}
             (set (:channels @registered-handler*)))))))

(deftest enqueue-perform-spawns-wave-and-queues-sound-test
  (let [enqueue-state! (var-get #'cn.li.ac.content.ability.vecmanip.directed-blastwave-fx/enqueue-state!)
        sound-calls* (atom [])]
    (with-redefs [client-sounds/queue-current-sound-effect! (fn [payload]
                                                              (swap! sound-calls* conj payload)
                                                              nil)
                  rand-int (fn [_] 0)
                  rand (fn [] 0.5)]
      (level-effects/update-effect-state! :directed-blastwave
        enqueue-state!
        (event "ctx-wave"
               {:mode :perform
                :pos {:x 1.0 :y 2.0 :z 3.0}
                :look-dir {:x 0.0 :y 0.0 :z 1.0}
                :charge-ticks 20}))
      (is (= 1 (count (get (:waves (blastwave-fx/directed-blastwave-fx-snapshot)) [:ctx "ctx-wave"]))))
      (is (= 1 (count @sound-calls*)))
      (is (= "my_mod:vecmanip.directed_blast"
             (:sound-id (first @sound-calls*)))))))

(deftest tick-clears-finished-state-and-expires-wave-test
  (let [enqueue-state! (var-get #'cn.li.ac.content.ability.vecmanip.directed-blastwave-fx/enqueue-state!)
        tick-state! (var-get #'cn.li.ac.content.ability.vecmanip.directed-blastwave-fx/tick-state!)]
    (with-redefs [client-sounds/queue-current-sound-effect! (fn [& _] nil)
                  rand-int (fn [_] 0)
                  rand (fn [] 0.5)]
      (level-effects/update-effect-state! :directed-blastwave
        enqueue-state!
        (event "ctx-tick" {:mode :start :source-player-id "player-a"}))
      (level-effects/update-effect-state! :directed-blastwave
        enqueue-state!
        (event "ctx-tick" {:mode :end :performed? false}))
      (level-effects/update-effect-state! :directed-blastwave
        (fn [store _]
          (tick-state! store))
        nil)
      (is (empty? (:effect-state (blastwave-fx/directed-blastwave-fx-snapshot))))
      (level-effects/update-effect-state! :directed-blastwave
        enqueue-state!
        (event "ctx-tick"
               {:mode :perform
                :pos {:x 1.0 :y 2.0 :z 3.0}
                :look-dir {:x 0.0 :y 0.0 :z 1.0}}))
      (dotimes [_ 15]
        (level-effects/update-effect-state! :directed-blastwave
          (fn [store _]
            (tick-state! store))
          nil))
      (is (empty? (:waves (blastwave-fx/directed-blastwave-fx-snapshot)))))))

(deftest two-owners-keep-blastwave-state-and-waves-independent-test
  (let [enqueue-state! (var-get #'cn.li.ac.content.ability.vecmanip.directed-blastwave-fx/enqueue-state!)
        tick-state! (var-get #'cn.li.ac.content.ability.vecmanip.directed-blastwave-fx/tick-state!)]
    (with-redefs [client-sounds/queue-current-sound-effect! (fn [& _] nil)
                  rand-int (fn [_] 0)
                  rand (fn [] 0.5)]
      (level-effects/update-effect-state! :directed-blastwave
        enqueue-state!
        (event "ctx-a" {:mode :start :source-player-id "player-a"}))
      (level-effects/update-effect-state! :directed-blastwave
        enqueue-state!
        (event "ctx-b" {:mode :start :source-player-id "player-b"}))
      (level-effects/update-effect-state! :directed-blastwave
        enqueue-state!
        (event "ctx-a" {:mode :update :charge-ticks 7 :punched? true :source-player-id "player-a"}))
      (level-effects/update-effect-state! :directed-blastwave
        enqueue-state!
        (event "ctx-b" {:mode :update :charge-ticks 11 :punched? false :source-player-id "player-b"}))
      (let [snapshot (blastwave-fx/directed-blastwave-fx-snapshot)]
        (is (= 7 (get-in (get (:effect-state snapshot) [:ctx "ctx-a"]) [:charge-ticks])))
        (is (= 11 (get-in (get (:effect-state snapshot) [:ctx "ctx-b"]) [:charge-ticks]))))
      (level-effects/update-effect-state! :directed-blastwave
        enqueue-state!
        (event "ctx-a" {:mode :end :performed? false :source-player-id "player-a"}))
      (level-effects/update-effect-state! :directed-blastwave
        (fn [store _]
          (tick-state! store))
        nil)
      (let [snapshot (blastwave-fx/directed-blastwave-fx-snapshot)]
        (is (nil? (get (:effect-state snapshot) [:ctx "ctx-a"])))
        (is (= 11 (get-in (get (:effect-state snapshot) [:ctx "ctx-b"]) [:charge-ticks]))))
      (level-effects/update-effect-state! :directed-blastwave
        enqueue-state!
        (event "ctx-a"
               {:mode :perform
                :pos {:x 1.0 :y 2.0 :z 3.0}
                :look-dir {:x 0.0 :y 0.0 :z 1.0}
                :source-player-id "player-a"}))
      (level-effects/update-effect-state! :directed-blastwave
        enqueue-state!
        (event "ctx-b"
               {:mode :perform
                :pos {:x 10.0 :y 2.0 :z 3.0}
                :look-dir {:x 1.0 :y 0.0 :z 0.0}
                :source-player-id "player-b"}))
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
  (let [runtime-a (level-effects/create-level-effect-runtime)
        runtime-b (level-effects/create-level-effect-runtime)
        enqueue-state! (var-get #'cn.li.ac.content.ability.vecmanip.directed-blastwave-fx/enqueue-state!)]
    (with-redefs [client-sounds/queue-current-sound-effect! (fn [& _] nil)
                  rand-int (fn [_] 0)
                  rand (fn [] 0.5)]
      (level-effects/call-with-level-effect-runtime
        runtime-a
        (fn []
          (level-effects/update-effect-state! :directed-blastwave
            enqueue-state!
            (event "ctx-a" {:mode :start :source-player-id "player-a"}))
          (level-effects/update-effect-state! :directed-blastwave
            enqueue-state!
            (event "ctx-a" {:mode :update :charge-ticks 9 :punched? true :source-player-id "player-a"}))
          (level-effects/update-effect-state! :directed-blastwave
            enqueue-state!
            (event "ctx-a"
                   {:mode :perform
                    :pos {:x 1.0 :y 2.0 :z 3.0}
                    :look-dir {:x 0.0 :y 0.0 :z 1.0}
                    :source-player-id "player-a"}))
          (is (= 9 (get-in (blastwave-fx/directed-blastwave-fx-snapshot)
                           [:effect-state [:ctx "ctx-a"] :charge-ticks])))
          (is (= 1 (count (get (:waves (blastwave-fx/directed-blastwave-fx-snapshot)) [:ctx "ctx-a"]))))))
      (level-effects/call-with-level-effect-runtime
        runtime-b
        (fn []
          (is (= (blastwave-fx/default-directed-blastwave-fx-runtime-state)
                 (blastwave-fx/directed-blastwave-fx-snapshot)))
          (level-effects/update-effect-state! :directed-blastwave
            enqueue-state!
            (event "ctx-b" {:mode :start :source-player-id "player-b"}))
          (is (= #{[:ctx "ctx-b"]}
                 (set (keys (:effect-state (blastwave-fx/directed-blastwave-fx-snapshot))))))))
      (level-effects/call-with-level-effect-runtime
        runtime-a
        (fn []
          (is (= 9 (get-in (blastwave-fx/directed-blastwave-fx-snapshot)
                           [:effect-state [:ctx "ctx-a"] :charge-ticks]))))))))

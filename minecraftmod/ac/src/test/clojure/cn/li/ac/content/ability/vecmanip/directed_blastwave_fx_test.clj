(ns cn.li.ac.content.ability.vecmanip.directed-blastwave-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.content.ability.vecmanip.directed-blastwave-fx :as blastwave-fx]))

(defn- reset-fixture [f]
  (try
        (level-effects/reset-level-effect-registry-for-test!)
        (blastwave-fx/reset-directed-blastwave-fx-for-test!)
        (f)
        (finally
          (blastwave-fx/reset-directed-blastwave-fx-for-test!)
          (level-effects/reset-level-effect-registry-for-test!))))

(use-fixtures :each reset-fixture)

(defn- event
  [ctx-id payload]
  {:payload payload
   :ctx-id ctx-id
   :channel :directed-blastwave/fx-perform
   :owner-key [:ctx ctx-id]})

(deftest init-registers-owner-aware-directed-blastwave-fx-test
  (let [registered-level* (atom nil)
        registered-topics* (atom #{})]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                         (reset! registered-level* [effect-id effect-map])
                                                         nil)
                  fx-registry/register-fx-channel! (fn [topic _handler]
                                                      (swap! registered-topics* conj topic)
                                                      nil)]
      (blastwave-fx/init!)
      (is (= :directed-blastwave (first @registered-level*)))
      (is (fn? (:enqueue-state-fn (second @registered-level*))))
      (is (= #{:directed-blastwave/fx-start
               :directed-blastwave/fx-update
               :directed-blastwave/fx-perform
               :directed-blastwave/fx-end}
             @registered-topics*)))))

(deftest enqueue-perform-spawns-wave-and-queues-sound-test
  (let [
        sound-calls* (atom [])]
    (with-redefs [client-sounds/queue-current-sound-effect! (fn [payload]
                                                              (swap! sound-calls* conj payload)
                                                              nil)
                  rand-int (fn [_] 0)
                  rand (fn [] 0.5)]
      (arc-beam/enqueue-for-test! :directed-blastwave "ctx-wave" :directed-blastwave/fx-perform
               {:mode :perform
                :pos {:x 1.0 :y 2.0 :z 3.0}
                :look-dir {:x 0.0 :y 0.0 :z 1.0}
                :charge-ticks 20})
      (is (= 1 (count (get (:waves (blastwave-fx/directed-blastwave-fx-snapshot)) [:ctx "ctx-wave"]))))
      (is (= 1 (count @sound-calls*)))
      (is (= "my_mod:vecmanip.directed_blast"
             (:sound-id (first @sound-calls*)))))))

(deftest tick-clears-finished-state-and-expires-wave-test
  (do
    (with-redefs [client-sounds/queue-current-sound-effect! (fn [& _] nil)
                  rand-int (fn [_] 0)
                  rand (fn [] 0.5)]
      (arc-beam/enqueue-for-test! :directed-blastwave "ctx-tick" :directed-blastwave/fx-perform {:mode :start :source-player-id "player-a"})
      (arc-beam/enqueue-for-test! :directed-blastwave "ctx-tick" :directed-blastwave/fx-perform {:mode :end :performed? false})
      (level-effects/update-effect-state! :directed-blastwave
        (fn [store] (arc-beam/effect-tick-state! :level :directed-blastwave store))
        nil)
      (is (empty? (:effect-state (blastwave-fx/directed-blastwave-fx-snapshot))))
      (arc-beam/enqueue-for-test! :directed-blastwave "ctx-tick" :directed-blastwave/fx-perform
               {:mode :perform
                :pos {:x 1.0 :y 2.0 :z 3.0}
                :look-dir {:x 0.0 :y 0.0 :z 1.0}})
      (dotimes [_ 15]
        (level-effects/update-effect-state! :directed-blastwave
          (fn [store] (arc-beam/effect-tick-state! :level :directed-blastwave store))
          nil))
      (is (empty? (:waves (blastwave-fx/directed-blastwave-fx-snapshot)))))))

(deftest two-owners-keep-blastwave-state-and-waves-independent-test
  (do
    (with-redefs [client-sounds/queue-current-sound-effect! (fn [& _] nil)
                  rand-int (fn [_] 0)
                  rand (fn [] 0.5)]
      (arc-beam/enqueue-for-test! :directed-blastwave "ctx-a" :directed-blastwave/fx-perform {:mode :start :source-player-id "player-a"})
      (arc-beam/enqueue-for-test! :directed-blastwave "ctx-b" :directed-blastwave/fx-perform {:mode :start :source-player-id "player-b"})
      (arc-beam/enqueue-for-test! :directed-blastwave "ctx-a" :directed-blastwave/fx-perform {:mode :update :charge-ticks 7 :punched? true :source-player-id "player-a"})
      (arc-beam/enqueue-for-test! :directed-blastwave "ctx-b" :directed-blastwave/fx-perform {:mode :update :charge-ticks 11 :punched? false :source-player-id "player-b"})
      (let [snapshot (blastwave-fx/directed-blastwave-fx-snapshot)]
        (is (= 7 (get-in (get (:effect-state snapshot) [:ctx "ctx-a"]) [:charge-ticks])))
        (is (= 11 (get-in (get (:effect-state snapshot) [:ctx "ctx-b"]) [:charge-ticks]))))
      (arc-beam/enqueue-for-test! :directed-blastwave "ctx-a" :directed-blastwave/fx-perform {:mode :end :performed? false :source-player-id "player-a"})
      (level-effects/update-effect-state! :directed-blastwave
        (fn [store] (arc-beam/effect-tick-state! :level :directed-blastwave store))
        nil)
      (let [snapshot (blastwave-fx/directed-blastwave-fx-snapshot)]
        (is (nil? (get (:effect-state snapshot) [:ctx "ctx-a"])))
        (is (= 11 (get-in (get (:effect-state snapshot) [:ctx "ctx-b"]) [:charge-ticks]))))
      (arc-beam/enqueue-for-test! :directed-blastwave "ctx-a" :directed-blastwave/fx-perform
               {:mode :perform
                :pos {:x 1.0 :y 2.0 :z 3.0}
                :look-dir {:x 0.0 :y 0.0 :z 1.0}
                :source-player-id "player-a"})
      (arc-beam/enqueue-for-test! :directed-blastwave "ctx-b" :directed-blastwave/fx-perform
               {:mode :perform
                :pos {:x 10.0 :y 2.0 :z 3.0}
                :look-dir {:x 1.0 :y 0.0 :z 0.0}
                :source-player-id "player-b"})
      (let [snapshot (blastwave-fx/directed-blastwave-fx-snapshot)]
        (is (= #{[:ctx "ctx-a"] [:ctx "ctx-b"]}
               (set (keys (:waves snapshot)))))
        (is (= 1 (count (get (:waves snapshot) [:ctx "ctx-a"]))))
        (is (= 1 (count (get (:waves snapshot) [:ctx "ctx-b"]))))
        (blastwave-fx/clear-directed-blastwave-owner! [:ctx "ctx-a"])
        (let [after-clear (blastwave-fx/directed-blastwave-fx-snapshot)]
          (is (nil? (get (:waves after-clear) [:ctx "ctx-a"])))
          (is (= 1 (count (get (:waves after-clear) [:ctx "ctx-b"])))))))))



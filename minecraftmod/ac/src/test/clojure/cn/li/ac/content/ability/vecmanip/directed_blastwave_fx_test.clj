(ns cn.li.ac.content.ability.vecmanip.directed-blastwave-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.content.ability.vecmanip.directed-blastwave-fx :as blastwave-fx]))

(defn- reset-fixture [f]
  (reset! @#'cn.li.ac.content.ability.vecmanip.directed-blastwave-fx/effect-state nil)
  (reset! @#'cn.li.ac.content.ability.vecmanip.directed-blastwave-fx/waves [])
  (f)
  (reset! @#'cn.li.ac.content.ability.vecmanip.directed-blastwave-fx/effect-state nil)
  (reset! @#'cn.li.ac.content.ability.vecmanip.directed-blastwave-fx/waves []))

(use-fixtures :each reset-fixture)

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
                  level-effects/enqueue-level-effect! (fn [effect-id payload]
                                                        (swap! level-enqueued* conj [effect-id payload])
                                                        nil)]
      (blastwave-fx/init!)
      (@handler* "ctx-1" :directed-blastwave/fx-start nil)
      (@handler* "ctx-1" :directed-blastwave/fx-update {:charge-ticks 11 :punched? true})
      (@handler* "ctx-1" :directed-blastwave/fx-perform {:pos {:x 1.0 :y 2.0 :z 3.0}
                                                          :look-dir {:x 0.0 :y 0.0 :z 1.0}
                                                          :charge-ticks 17})
      (@handler* "ctx-1" :directed-blastwave/fx-end {:performed? false})
      (is (= [[:directed-blastwave {:mode :start}]
              [:directed-blastwave {:mode :update :charge-ticks 11 :punched? true}]
              [:directed-blastwave {:mode :perform
                                    :pos {:x 1.0 :y 2.0 :z 3.0}
                                    :look-dir {:x 0.0 :y 0.0 :z 1.0}
                                    :charge-ticks 17}]
              [:directed-blastwave {:mode :end :performed? false}]]
             @level-enqueued*)))))

(deftest enqueue-perform-spawns-wave-and-queues-sound-test
  (let [enqueue! @#'cn.li.ac.content.ability.vecmanip.directed-blastwave-fx/enqueue!
        sound-calls* (atom [])]
    (with-redefs [client-sounds/queue-sound-effect! (fn [payload]
                                                      (swap! sound-calls* conj payload)
                                                      nil)
                  rand-int (fn [_] 0)
                  rand (fn [] 0.5)]
      (enqueue! {:mode :perform
                 :pos {:x 1.0 :y 2.0 :z 3.0}
                 :look-dir {:x 0.0 :y 0.0 :z 1.0}
                 :charge-ticks 20})
      (is (= 1 (count @@#'cn.li.ac.content.ability.vecmanip.directed-blastwave-fx/waves)))
      (is (= 1 (count @sound-calls*)))
      (is (= "my_mod:vecmanip.directed_blast"
             (:sound-id (first @sound-calls*)))))))

(deftest tick-clears-finished-state-and-expires-wave-test
  (let [enqueue! @#'cn.li.ac.content.ability.vecmanip.directed-blastwave-fx/enqueue!
        tick! @#'cn.li.ac.content.ability.vecmanip.directed-blastwave-fx/tick!]
    (with-redefs [client-sounds/queue-sound-effect! (fn [_] nil)]
      (enqueue! {:mode :start})
      (enqueue! {:mode :end :performed? false})
      (tick!)
      (is (nil? @@#'cn.li.ac.content.ability.vecmanip.directed-blastwave-fx/effect-state))
      (enqueue! {:mode :perform
                 :pos {:x 1.0 :y 2.0 :z 3.0}
                 :look-dir {:x 0.0 :y 0.0 :z 1.0}})
      (dotimes [_ 15] (tick!))
      (is (empty? @@#'cn.li.ac.content.ability.vecmanip.directed-blastwave-fx/waves)))))

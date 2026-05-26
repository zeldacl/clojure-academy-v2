(ns cn.li.ac.content.ability.teleporter.location-teleport-fx-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.content.ability.teleporter.location-teleport-fx :as lfx]))

(deftest init-registers-location-teleport-success-channel-test
  (let [registered-level* (atom nil)
        registered-handler* (atom nil)]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                         (reset! registered-level* [effect-id effect-map])
                                                         nil)
                  fx-registry/register-fx-channels! (fn [channels handler]
                                                      (reset! registered-handler* {:channels channels
                                                                                   :handler handler})
                                                      nil)]
      (lfx/init!)
      (is (= :location-teleport (first @registered-level*)))
      (is (= #{:location-teleport/fx-perform-success}
             (set (:channels @registered-handler*)))))))

(deftest fx-handler-routes-success-event-to-level-enqueue-test
  (let [handler* (atom nil)
        enqueued* (atom [])]
    (with-redefs [level-effects/register-level-effect! (fn [& _] nil)
                  fx-registry/register-fx-channels! (fn [_channels handler]
                                                      (reset! handler* handler)
                                                      nil)
                  level-effects/enqueue-level-effect! (fn [effect-id payload fx-context]
                                                        (swap! enqueued* conj [effect-id payload fx-context])
                                                        nil)]
      (lfx/init!)
      (@handler* "ctx-1" :location-teleport/fx-perform-success {:target {:x 1.0 :y 64.0 :z 2.0}
                                                                  :distance 10.0})
      (is (= [[:location-teleport {:mode :perform-success
                                   :target {:x 1.0 :y 64.0 :z 2.0}
                                   :distance 10.0}
               {:ctx-id "ctx-1" :channel :location-teleport/fx-perform-success}]]
             @enqueued*)))))

(deftest enqueue-perform-success-plays-teleport-sound-test
  (let [sounds* (atom [])
        enqueue! (var-get #'cn.li.ac.content.ability.teleporter.location-teleport-fx/enqueue!)]
    (with-redefs [client-sounds/queue-sound-effect! (fn [payload]
                                                       (swap! sounds* conj payload)
                                                       nil)]
      (enqueue! {:payload {:mode :perform-success :target {:x 1.0 :y 2.0 :z 3.0}}})
      (is (= 1 (count @sounds*)))
      (is (= "my_mod:tp.tp" (:sound-id (first @sounds*)))))))

(deftest enqueue-non-success-mode-does-not-play-sound-test
  (let [sounds* (atom 0)
        enqueue! (var-get #'cn.li.ac.content.ability.teleporter.location-teleport-fx/enqueue!)]
    (with-redefs [client-sounds/queue-sound-effect! (fn [_] (swap! sounds* inc) nil)]
      (enqueue! {:payload {:mode :ignored}})
      (is (= 0 @sounds*)))))
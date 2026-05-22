(ns cn.li.ac.content.ability.vecmanip.directed-shock-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.hand-effects :as hand-effects]
            [cn.li.ac.content.ability.vecmanip.directed-shock-fx :as dsfx]))

(defn- reset-state-fixture [f]
  (reset! @#'cn.li.ac.content.ability.vecmanip.directed-shock-fx/effect-state nil)
  (f)
  (reset! @#'cn.li.ac.content.ability.vecmanip.directed-shock-fx/effect-state nil))

(defn- effect-state-value []
  @(deref #'cn.li.ac.content.ability.vecmanip.directed-shock-fx/effect-state))

(use-fixtures :each reset-state-fixture)

(deftest init-registers-directed-shock-fx-channels-test
  (let [registered-hand* (atom nil)
        registered-handler* (atom nil)]
    (with-redefs [hand-effects/register-hand-effect! (fn [effect-id effect-map]
                                                       (reset! registered-hand* [effect-id effect-map]))
                  fx-registry/register-fx-channels! (fn [channels handler]
                                                      (reset! registered-handler* {:channels channels
                                                                                   :handler handler}))]
      (dsfx/init!)
      (is (= :directed-shock (first @registered-hand*)))
      (is (= #{:directed-shock/fx-start
               :directed-shock/fx-perform
               :directed-shock/fx-end}
             (set (:channels @registered-handler*)))))))

(deftest fx-handler-routes-start-perform-end-test
  (let [handler* (atom nil)
        hand-enqueued* (atom [])]
    (with-redefs [hand-effects/register-hand-effect! (fn [& _] nil)
                  fx-registry/register-fx-channels! (fn [_ handler]
                                                      (reset! handler* handler))
                  hand-effects/enqueue-hand-effect! (fn [effect-id payload]
                                                      (swap! hand-enqueued* conj [effect-id payload]))]
      (dsfx/init!)
      (@handler* "ctx-1" :directed-shock/fx-start nil)
      (@handler* "ctx-1" :directed-shock/fx-perform nil)
      (@handler* "ctx-1" :directed-shock/fx-end {:performed? false})
      (is (= [[:directed-shock {:mode :start}]
              [:directed-shock {:mode :perform}]
              [:directed-shock {:mode :end :performed? false}]]
             @hand-enqueued*)))))

(deftest enqueue-perform-plays-sound-once-test
  (let [enqueue! @#'cn.li.ac.content.ability.vecmanip.directed-shock-fx/enqueue!
        sound-calls* (atom [])]
    (with-redefs [client-sounds/queue-sound-effect! (fn [payload]
                                                      (swap! sound-calls* conj payload))]
      (enqueue! {:mode :perform})
      (is (= 1 (count @sound-calls*)))
      (is (= "my_mod:vecmanip.directed_shock"
             (:sound-id (first @sound-calls*)))))))

(deftest end-payload-respects-performed-flag-test
  (let [enqueue! @#'cn.li.ac.content.ability.vecmanip.directed-shock-fx/enqueue!]
    (with-redefs [cn.li.ac.content.ability.vecmanip.directed-shock-fx/now-ms (fn [] 1000)]
      (enqueue! {:mode :perform})
      (enqueue! {:mode :end :performed? true})
      (is (= :punch (:stage (effect-state-value))))
      (enqueue! {:mode :end :performed? false})
      (is (nil? (effect-state-value))))))

(deftest punch-tick-clears-expired-state-test
  (let [enqueue! @#'cn.li.ac.content.ability.vecmanip.directed-shock-fx/enqueue!
        tick! @#'cn.li.ac.content.ability.vecmanip.directed-shock-fx/tick!
        now* (atom 1000)]
    (with-redefs [cn.li.ac.content.ability.vecmanip.directed-shock-fx/now-ms (fn [] @now*)
                  client-sounds/queue-sound-effect! (fn [_] nil)]
      (enqueue! {:mode :perform})
      (swap! now* + 301)
      (tick!)
      (is (nil? (effect-state-value))))))

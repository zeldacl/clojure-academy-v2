(ns cn.li.ac.content.ability.vecmanip.directed-shock-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.hand-effects :as hand-effects]
            [cn.li.ac.content.ability.vecmanip.directed-shock-fx :as dsfx]))

(defn- reset-state-fixture [f]
  (dsfx/reset-directed-shock-fx-for-test!)
  (f)
  (dsfx/reset-directed-shock-fx-for-test!))

(defn- owner-state [ctx-id]
  (get (:effect-state (dsfx/directed-shock-fx-snapshot)) [:ctx ctx-id]))

(defn- payload [ctx-id payload]
  (merge {:owner-key [:ctx ctx-id]
          :ctx-id ctx-id}
         payload))

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
      (is (= [[:directed-shock {:owner-key [:ctx "ctx-1"]
                :ctx-id "ctx-1"
                :channel :directed-shock/fx-start
                :mode :start}]
          [:directed-shock {:owner-key [:ctx "ctx-1"]
                :ctx-id "ctx-1"
                :channel :directed-shock/fx-perform
                :mode :perform}]
          [:directed-shock {:owner-key [:ctx "ctx-1"]
                :ctx-id "ctx-1"
                :channel :directed-shock/fx-end
                :mode :end
                :performed? false}]]
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
      (enqueue! (payload "ctx-1" {:mode :perform}))
      (enqueue! (payload "ctx-1" {:mode :end :performed? true}))
      (is (= :punch (:stage (owner-state "ctx-1"))))
      (enqueue! (payload "ctx-1" {:mode :end :performed? false}))
      (is (nil? (owner-state "ctx-1"))))))

(deftest punch-tick-clears-expired-state-test
  (let [enqueue! @#'cn.li.ac.content.ability.vecmanip.directed-shock-fx/enqueue!
        tick! @#'cn.li.ac.content.ability.vecmanip.directed-shock-fx/tick!
        now* (atom 1000)]
    (with-redefs [cn.li.ac.content.ability.vecmanip.directed-shock-fx/now-ms (fn [] @now*)
                  client-sounds/queue-sound-effect! (fn [_] nil)]
      (enqueue! {:mode :perform})
      (swap! now* + 301)
      (tick!)
      (is (empty? (:effect-state (dsfx/directed-shock-fx-snapshot)))))))

(deftest two-owners-keep-directed-shock-state-independent-test
  (let [enqueue! @#'cn.li.ac.content.ability.vecmanip.directed-shock-fx/enqueue!]
    (with-redefs [cn.li.ac.content.ability.vecmanip.directed-shock-fx/now-ms (fn [] 1000)
                  client-sounds/queue-sound-effect! (fn [_] nil)]
      (enqueue! (payload "ctx-a" {:mode :start}))
      (enqueue! (payload "ctx-b" {:mode :perform}))
      (is (= :prepare (:stage (owner-state "ctx-a"))))
      (is (= :punch (:stage (owner-state "ctx-b"))))
      (enqueue! (payload "ctx-a" {:mode :end :performed? false}))
      (is (nil? (owner-state "ctx-a")))
      (is (= :punch (:stage (owner-state "ctx-b"))))
      (dsfx/clear-directed-shock-owner! [:ctx "ctx-b"])
      (is (empty? (:effect-state (dsfx/directed-shock-fx-snapshot)))))))

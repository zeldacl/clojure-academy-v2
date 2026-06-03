(ns cn.li.ac.content.ability.vecmanip.directed-shock-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.hand-effects :as hand-effects]
            [cn.li.ac.content.ability.vecmanip.directed-shock-fx :as dsfx]))

(defn- with-fresh-directed-shock-runtime [f]
  (hand-effects/call-with-hand-effect-runtime
    (hand-effects/create-hand-effect-runtime)
    (fn []
      (try
        (hand-effects/reset-hand-effect-registry-for-test!)
        (dsfx/reset-directed-shock-fx-for-test!)
        (f)
        (finally
          (hand-effects/reset-hand-effect-registry-for-test!)
          (dsfx/reset-directed-shock-fx-for-test!))))))

(defn- owner-state [ctx-id]
  (get (:effect-state (dsfx/directed-shock-fx-snapshot)) [:ctx ctx-id]))

(defn- payload [ctx-id payload]
  (merge {:owner-key [:ctx ctx-id]
          :ctx-id ctx-id}
         payload))

(use-fixtures :each with-fresh-directed-shock-runtime)

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
  (let [enqueue-state! @#'cn.li.ac.content.ability.vecmanip.directed-shock-fx/enqueue-state!
        sound-calls* (atom [])]
    (with-redefs [client-sounds/queue-current-sound-effect! (fn [payload]
                                                              (swap! sound-calls* conj payload))]
      (enqueue-state! (dsfx/default-directed-shock-fx-state) {:mode :perform})
      (is (= 1 (count @sound-calls*)))
      (is (= "my_mod:vecmanip.directed_shock"
             (:sound-id (first @sound-calls*)))))))

(deftest end-payload-respects-performed-flag-test
  (let [enqueue-state! @#'cn.li.ac.content.ability.vecmanip.directed-shock-fx/enqueue-state!]
    (with-redefs [cn.li.ac.content.ability.vecmanip.directed-shock-fx/now-ms (fn [] 1000)
                  client-sounds/queue-current-sound-effect! (fn [_] nil)]
      (hand-effects/update-effect-state! :directed-shock enqueue-state! (payload "ctx-1" {:mode :perform}))
      (hand-effects/update-effect-state! :directed-shock enqueue-state! (payload "ctx-1" {:mode :end :performed? true}))
      (is (= :punch (:stage (owner-state "ctx-1"))))
      (hand-effects/update-effect-state! :directed-shock enqueue-state! (payload "ctx-1" {:mode :end :performed? false}))
      (is (nil? (owner-state "ctx-1"))))))

(deftest punch-tick-clears-expired-state-test
  (let [enqueue-state! @#'cn.li.ac.content.ability.vecmanip.directed-shock-fx/enqueue-state!
        tick-state! @#'cn.li.ac.content.ability.vecmanip.directed-shock-fx/tick-state!
        now* (atom 1000)]
    (with-redefs [cn.li.ac.content.ability.vecmanip.directed-shock-fx/now-ms (fn [] @now*)
                  client-sounds/queue-current-sound-effect! (fn [_] nil)]
      (hand-effects/update-effect-state! :directed-shock enqueue-state! {:mode :perform :owner-key [:ctx "ctx-a"]})
      (swap! now* + 301)
      (hand-effects/update-effect-state! :directed-shock tick-state!)
      (is (empty? (:effect-state (dsfx/directed-shock-fx-snapshot)))))))

(deftest two-owners-keep-directed-shock-state-independent-test
  (let [enqueue-state! @#'cn.li.ac.content.ability.vecmanip.directed-shock-fx/enqueue-state!]
    (with-redefs [cn.li.ac.content.ability.vecmanip.directed-shock-fx/now-ms (fn [] 1000)
                  client-sounds/queue-current-sound-effect! (fn [_] nil)]
      (hand-effects/update-effect-state! :directed-shock enqueue-state! (payload "ctx-a" {:mode :start}))
      (hand-effects/update-effect-state! :directed-shock enqueue-state! (payload "ctx-b" {:mode :perform}))
      (is (= :prepare (:stage (owner-state "ctx-a"))))
      (is (= :punch (:stage (owner-state "ctx-b"))))
      (hand-effects/update-effect-state! :directed-shock enqueue-state! (payload "ctx-a" {:mode :end :performed? false}))
      (is (nil? (owner-state "ctx-a")))
      (is (= :punch (:stage (owner-state "ctx-b"))))
      (dsfx/clear-directed-shock-owner! [:ctx "ctx-b"])
      (is (empty? (:effect-state (dsfx/directed-shock-fx-snapshot)))))))

(deftest directed-shock-fx-runtime-isolation-test
  (let [runtime-a (hand-effects/create-hand-effect-runtime)
        runtime-b (hand-effects/create-hand-effect-runtime)
        enqueue-state! @#'cn.li.ac.content.ability.vecmanip.directed-shock-fx/enqueue-state!]
    (with-redefs [cn.li.ac.content.ability.vecmanip.directed-shock-fx/now-ms (fn [] 1000)
                  client-sounds/queue-current-sound-effect! (fn [_] nil)]
      (hand-effects/call-with-hand-effect-runtime
        runtime-a
        (fn []
          (hand-effects/update-effect-state! :directed-shock enqueue-state! (payload "ctx-a" {:mode :perform}))
          (is (= :punch (:stage (owner-state "ctx-a"))))))
      (hand-effects/call-with-hand-effect-runtime
        runtime-b
        (fn []
          (is (= {:effect-state {}}
                 (dsfx/directed-shock-fx-snapshot)))
          (hand-effects/update-effect-state! :directed-shock enqueue-state! (payload "ctx-b" {:mode :start}))
          (is (= #{[:ctx "ctx-b"]}
                 (set (keys (:effect-state (dsfx/directed-shock-fx-snapshot))))))))
      (hand-effects/call-with-hand-effect-runtime
        runtime-a
        (fn []
          (is (= :punch (:stage (owner-state "ctx-a")))))))))

(deftest directed-shock-fx-snapshot-default-without-registered-state-test
  (is (= {:effect-state {}}
         (dsfx/directed-shock-fx-snapshot))))

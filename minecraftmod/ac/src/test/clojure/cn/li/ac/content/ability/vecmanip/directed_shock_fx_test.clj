(ns cn.li.ac.content.ability.vecmanip.directed-shock-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.hand-effects :as hand-effects]
            [cn.li.ac.content.ability.vecmanip.directed-shock-fx :as dsfx]))

(defn- invoke-hand-enqueue! [ctx-id channel payload]
  (arc-beam/enqueue-for-test! :directed-shock ctx-id channel payload {:runtime :hand}))

(defn- with-fresh-directed-shock-runtime [f]
  (try
    (hand-effects/reset-hand-effect-registry-for-test!)
    (dsfx/reset-fx-for-test!)
    (dsfx/init!)
    (f)
    (finally
      (hand-effects/reset-hand-effect-registry-for-test!)
      (dsfx/reset-fx-for-test!))))

(defn- owner-state [ctx-id]
  (get (:effect-state (dsfx/fx-snapshot)) [:ctx ctx-id]))

(use-fixtures :each with-fresh-directed-shock-runtime)

(deftest init-registers-directed-shock-fx-channels-test
  (let [registered-hand* (atom nil)
        registered-topics* (atom #{})]
    (with-redefs [hand-effects/register-hand-effect! (fn [effect-id effect-map]
                                                       (reset! registered-hand* [effect-id effect-map]))
                  fx-registry/register-fx-channel! (fn [topic _handler]
                                                      (swap! registered-topics* conj topic))]
      (dsfx/init!)
      (is (= :directed-shock (first @registered-hand*)))
      (is (= #{:directed-shock/fx-start
               :directed-shock/fx-perform
               :directed-shock/fx-end}
             @registered-topics*)))))

(deftest fx-handler-routes-start-perform-end-test
  (let [handlers* (atom {})
        hand-enqueued* (atom [])]
    (with-redefs [hand-effects/register-hand-effect! (fn [& _] nil)
                  fx-registry/register-fx-channel! (fn [topic handler]
                                                      (swap! handlers* assoc topic handler))
                  hand-effects/enqueue-hand-effect! (fn [effect-id ctx-id channel payload & opts]
                                                      (swap! hand-enqueued* conj (into [effect-id ctx-id channel payload] opts)))]
      (dsfx/init!)
      ((get @handlers* :directed-shock/fx-start) "ctx-1" :directed-shock/fx-start nil)
      ((get @handlers* :directed-shock/fx-perform) "ctx-1" :directed-shock/fx-perform nil)
      ((get @handlers* :directed-shock/fx-end) "ctx-1" :directed-shock/fx-end {:performed? false})
      (is (= [[:directed-shock "ctx-1" :directed-shock/fx-start {:mode :start} :owner-key [:ctx "ctx-1"]]
              [:directed-shock "ctx-1" :directed-shock/fx-perform {:mode :perform} :owner-key [:ctx "ctx-1"]]
              [:directed-shock "ctx-1" :directed-shock/fx-end {:mode :end :performed? false} :owner-key [:ctx "ctx-1"]]]
             @hand-enqueued*)))))

(deftest enqueue-perform-plays-sound-once-test
  (let [
        sound-calls* (atom [])]
    (with-redefs [client-sounds/queue-current-sound-effect! (fn [payload]
                                                              (swap! sound-calls* conj payload))]
      (arc-beam/enqueue-for-test! :directed-shock "ctx-1" :directed-shock/fx-perform {:mode :perform} {:runtime :hand})
      (is (= 1 (count @sound-calls*)))
      (is (= "my_mod:vecmanip.directed_shock"
             (:sound-id (first @sound-calls*)))))))

(deftest end-payload-respects-performed-flag-test
  (with-redefs [client-sounds/queue-current-sound-effect! (fn [_] nil)]
      (invoke-hand-enqueue! "ctx-1" :directed-shock/fx-perform {:mode :perform})
      (invoke-hand-enqueue! "ctx-1" :directed-shock/fx-end {:mode :end :performed? true})
      (is (= :punch (:stage (owner-state "ctx-1"))))
      (invoke-hand-enqueue! "ctx-1" :directed-shock/fx-end {:mode :end :performed? false})
      (is (nil? (owner-state "ctx-1")))))

(deftest punch-tick-clears-expired-state-test
  (let [
        now* (atom 1000)]
    (with-redefs [cn.li.ac.ability.client.fx-templates.arc-beam.impl.directed-shock/now-ms (fn [] @now*)
                  client-sounds/queue-current-sound-effect! (fn [_] nil)]
      (invoke-hand-enqueue! "ctx-a" :directed-shock/fx-perform {:mode :perform})
      (swap! now* + 301)
      (hand-effects/update-effect-state! :directed-shock
        (fn [store] (arc-beam/effect-tick-state! :hand :directed-shock store)))
      (is (empty? (:effect-state (dsfx/fx-snapshot)))))))

(deftest two-owners-keep-directed-shock-state-independent-test
  (with-redefs [client-sounds/queue-current-sound-effect! (fn [_] nil)]
    (invoke-hand-enqueue! "ctx-a" :directed-shock/fx-start {:mode :start})
    (invoke-hand-enqueue! "ctx-b" :directed-shock/fx-perform {:mode :perform})
    (is (= :prepare (:stage (owner-state "ctx-a"))))
    (is (= :punch (:stage (owner-state "ctx-b"))))
    (invoke-hand-enqueue! "ctx-a" :directed-shock/fx-end {:mode :end :performed? false})
    (is (nil? (owner-state "ctx-a")))
    (is (= :punch (:stage (owner-state "ctx-b"))))
    (dsfx/clear-fx-owner! [:ctx "ctx-b"])
    (is (empty? (:effect-state (dsfx/fx-snapshot))))))

(deftest fx-snapshot-default-without-registered-state-test
  (is (= {:effect-state {}}
         (dsfx/fx-snapshot))))

(ns cn.li.ac.content.ability.electromaster.mag-manip-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.hand-effects :as hand-effects]
            [cn.li.ac.content.ability.electromaster.mag-manip-fx :as mag-manip-fx]))

(defn- with-fresh-mag-manip-fx-runtime [f]
  (try
        (hand-effects/reset-hand-effect-registry-for-test!)
        (f)
        (finally
          (hand-effects/reset-hand-effect-registry-for-test!)
          (mag-manip-fx/reset-mag-manip-fx-for-test!))))

(use-fixtures :each with-fresh-mag-manip-fx-runtime)

(deftest init-registers-mag-manip-fx-channels-test
  (let [registered-hand* (atom nil)
        registered-topics* (atom #{})]
    (with-redefs [hand-effects/register-hand-effect! (fn [effect-id effect-map]
                                                       (reset! registered-hand* [effect-id effect-map])
                                                       nil)
                  fx-registry/register-fx-channel! (fn [topic _handler]
                                                      (swap! registered-topics* conj topic)
                                                      nil)]
      (mag-manip-fx/init!)
      (is (= :mag-manip (first @registered-hand*)))
      (is (= #{:mag-manip/fx-hold
               :mag-manip/fx-throw
               :mag-manip/fx-end}
             @registered-topics*)))))

(deftest fx-handler-routes-hold-throw-end-and-queues-sounds-test
  (let [handlers* (atom {})
        hand-enqueued* (atom [])]
    (with-redefs [hand-effects/register-hand-effect! (fn [& _] nil)
                  fx-registry/register-fx-channel! (fn [topic handler]
                                                      (swap! handlers* assoc topic handler)
                                                      nil)
                  hand-effects/enqueue-hand-effect! (fn [effect-id ctx-id channel payload & opts]
                                                      (swap! hand-enqueued* conj [effect-id payload])
                                                      nil)]
      (mag-manip-fx/init!)
      ((get @handlers* :mag-manip/fx-hold) "ctx-1" :mag-manip/fx-hold {:mode :hold-start :block-id "minecraft:iron_block"})
      ((get @handlers* :mag-manip/fx-hold) "ctx-1" :mag-manip/fx-hold {:mode :hold-loop :block-id "minecraft:iron_block"})
      ((get @handlers* :mag-manip/fx-throw) "ctx-1" :mag-manip/fx-throw {:start {:x 0.0 :y 0.0 :z 0.0}
                                               :end {:x 0.0 :y 0.0 :z 5.0}})
      ((get @handlers* :mag-manip/fx-end) "ctx-1" :mag-manip/fx-end {:mode :end :reason :performed})

      (is (= [[:mag-manip {:owner-key [:ctx "ctx-1"]
                           :ctx-id "ctx-1"
                           :channel :mag-manip/fx-hold
                           :mode :hold-start
                           :block-id "minecraft:iron_block"}]
              [:mag-manip {:owner-key [:ctx "ctx-1"]
                           :ctx-id "ctx-1"
                           :channel :mag-manip/fx-hold
                           :mode :hold-loop
                           :block-id "minecraft:iron_block"}]
              [:mag-manip {:owner-key [:ctx "ctx-1"]
                           :ctx-id "ctx-1"
                           :channel :mag-manip/fx-throw
                           :start {:x 0.0 :y 0.0 :z 0.0}
                           :end {:x 0.0 :y 0.0 :z 5.0}
                           :mode :throw}]
              [:mag-manip {:owner-key [:ctx "ctx-1"]
                           :ctx-id "ctx-1"
                           :channel :mag-manip/fx-end
                           :mode :end
                           :reason :performed}]]
             @hand-enqueued*)))))

(deftest two-owners-keep-mag-manip-state-independent-test
  (mag-manip-fx/reset-mag-manip-fx-for-test!)
  (let [enqueue-state! (var-get #'cn.li.ac.content.ability.electromaster.mag-manip-fx/enqueue-state!)]
    (with-redefs [client-sounds/current-effect-owner (fn [] {:client-session-id "mag-manip-test"})
                  client-sounds/queue-current-sound-effect! (fn [& _] nil)
                  client-sounds/queue-sound-effect! (fn [& _] nil)]
      (hand-effects/update-effect-state! :mag-manip enqueue-state! {:owner-key [:ctx "ctx-a"] :ctx-id "ctx-a" :mode :hold-start :block-id "minecraft:iron_block"})
      (hand-effects/update-effect-state! :mag-manip enqueue-state! {:owner-key [:ctx "ctx-b"] :ctx-id "ctx-b" :mode :hold-start :block-id "minecraft:gold_block"})
      (hand-effects/update-effect-state! :mag-manip enqueue-state! {:owner-key [:ctx "ctx-a"] :ctx-id "ctx-a" :mode :hold-loop :block-id "minecraft:copper_block"})
      (let [snapshot (mag-manip-fx/mag-manip-fx-snapshot)]
        (is (= "minecraft:copper_block" (:block-id (get (:states snapshot) [:ctx "ctx-a"]))))
        (is (= "minecraft:gold_block" (:block-id (get (:states snapshot) [:ctx "ctx-b"]))))
        (mag-manip-fx/clear-mag-manip-owner! [:ctx "ctx-a"])
        (let [snapshot (mag-manip-fx/mag-manip-fx-snapshot)]
          (is (nil? (get (:states snapshot) [:ctx "ctx-a"])))
          (is (some? (get (:states snapshot) [:ctx "ctx-b"]))))))))



(deftest mag-manip-fx-snapshot-default-without-registered-state-test
  (is (= {:states {}}
         (mag-manip-fx/mag-manip-fx-snapshot))))

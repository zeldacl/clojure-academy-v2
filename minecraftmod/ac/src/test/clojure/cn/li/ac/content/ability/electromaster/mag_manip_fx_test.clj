(ns cn.li.ac.content.ability.electromaster.mag-manip-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.hand-effects :as hand-effects]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.content.ability.electromaster.mag-manip-fx :as mag-manip-fx]))

(defn- with-fresh-mag-manip-fx-runtime [f]
  (mag-manip-fx/call-with-mag-manip-fx-runtime
    (mag-manip-fx/create-mag-manip-fx-runtime)
    (fn []
      (try
        (f)
        (finally
          (mag-manip-fx/reset-mag-manip-fx-for-test!))))))

(use-fixtures :each with-fresh-mag-manip-fx-runtime)

(deftest init-registers-mag-manip-fx-channels-test
  (let [registered-level* (atom nil)
        registered-hand* (atom nil)
        registered-handler* (atom nil)]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                         (reset! registered-level* [effect-id effect-map])
                                                         nil)
                  hand-effects/register-hand-effect! (fn [effect-id effect-map]
                                                       (reset! registered-hand* [effect-id effect-map])
                                                       nil)
                  fx-registry/register-fx-channels! (fn [channels handler]
                                                      (reset! registered-handler* {:channels channels
                                                                                   :handler handler})
                                                      nil)]
      (mag-manip-fx/init!)
      (is (= :mag-manip (first @registered-level*)))
      (is (= :mag-manip (first @registered-hand*)))
      (is (= #{:mag-manip/fx-hold
               :mag-manip/fx-throw
               :mag-manip/fx-end}
             (set (:channels @registered-handler*)))))))

(deftest fx-handler-routes-hold-throw-end-and-queues-sounds-test
  (let [handler* (atom nil)
        hand-enqueued* (atom [])
  level-enqueued* (atom [])]
    (with-redefs [level-effects/register-level-effect! (fn [& _] nil)
                  hand-effects/register-hand-effect! (fn [& _] nil)
                  fx-registry/register-fx-channels! (fn [_ handler]
                                                      (reset! handler* handler)
                                                      nil)
                  hand-effects/enqueue-hand-effect! (fn [effect-id payload]
                                                      (swap! hand-enqueued* conj [effect-id payload])
                                                      nil)
                  level-effects/enqueue-level-effect! (fn [effect-id payload fx-context]
                                                        (swap! level-enqueued* conj [effect-id payload fx-context])
                                                      nil)]
      (mag-manip-fx/init!)
      (@handler* "ctx-1" :mag-manip/fx-hold {:mode :hold-start :block-id "minecraft:iron_block"})
      (@handler* "ctx-1" :mag-manip/fx-hold {:mode :hold-loop :block-id "minecraft:iron_block"})
      (@handler* "ctx-1" :mag-manip/fx-throw {:start {:x 0.0 :y 0.0 :z 0.0}
                                               :end {:x 0.0 :y 0.0 :z 5.0}})
      (@handler* "ctx-1" :mag-manip/fx-end {:mode :end :reason :performed})

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
             @hand-enqueued*))
      (is (= (mapv (fn [[effect-id payload]]
                     [effect-id payload {:ctx-id "ctx-1" :channel (:channel payload)}])
                   @hand-enqueued*)
             @level-enqueued*)))))

(deftest two-owners-keep-mag-manip-state-independent-test
  (mag-manip-fx/reset-mag-manip-fx-for-test!)
  (let [enqueue! (var-get #'cn.li.ac.content.ability.electromaster.mag-manip-fx/enqueue!)]
    (with-redefs [client-sounds/current-effect-owner (fn [] {:client-session-id "mag-manip-test"})
                  client-sounds/queue-current-sound-effect! (fn [& _] nil)
                  client-sounds/queue-sound-effect! (fn [& _] nil)]
      (enqueue! {:owner-key [:ctx "ctx-a"] :ctx-id "ctx-a" :mode :hold-start :block-id "minecraft:iron_block"})
      (enqueue! {:owner-key [:ctx "ctx-b"] :ctx-id "ctx-b" :mode :hold-start :block-id "minecraft:gold_block"})
      (enqueue! {:owner-key [:ctx "ctx-a"] :ctx-id "ctx-a" :mode :hold-loop :block-id "minecraft:copper_block"})
      (let [snapshot (mag-manip-fx/mag-manip-fx-snapshot)]
        (is (= "minecraft:copper_block" (:block-id (get (:states snapshot) [:ctx "ctx-a"]))))
        (is (= "minecraft:gold_block" (:block-id (get (:states snapshot) [:ctx "ctx-b"]))))
        (mag-manip-fx/clear-mag-manip-owner! [:ctx "ctx-a"])
        (let [snapshot (mag-manip-fx/mag-manip-fx-snapshot)]
          (is (nil? (get (:states snapshot) [:ctx "ctx-a"])))
          (is (some? (get (:states snapshot) [:ctx "ctx-b"]))))))))

(deftest mag-manip-fx-runtime-isolation-test
  (let [runtime-a (mag-manip-fx/create-mag-manip-fx-runtime)
        runtime-b (mag-manip-fx/create-mag-manip-fx-runtime)
        enqueue! (var-get #'cn.li.ac.content.ability.electromaster.mag-manip-fx/enqueue!)]
    (with-redefs [client-sounds/current-effect-owner (fn [] {:client-session-id "mag-manip-test"})
                  client-sounds/queue-current-sound-effect! (fn [& _] nil)
                  client-sounds/queue-sound-effect! (fn [& _] nil)]
      (mag-manip-fx/call-with-mag-manip-fx-runtime
        runtime-a
        (fn []
          (enqueue! {:owner-key [:ctx "ctx-a"] :ctx-id "ctx-a" :mode :hold-start :block-id "minecraft:iron_block"})
          (is (= #{[:ctx "ctx-a"]}
                 (set (keys (:states (mag-manip-fx/mag-manip-fx-snapshot))))))))
      (mag-manip-fx/call-with-mag-manip-fx-runtime
        runtime-b
        (fn []
          (is (= {:states {} :current-owner-key nil}
                 (mag-manip-fx/mag-manip-fx-snapshot)))
          (enqueue! {:owner-key [:ctx "ctx-b"] :ctx-id "ctx-b" :mode :hold-start :block-id "minecraft:gold_block"})
          (is (= #{[:ctx "ctx-b"]}
                 (set (keys (:states (mag-manip-fx/mag-manip-fx-snapshot))))))))
      (mag-manip-fx/call-with-mag-manip-fx-runtime
        runtime-a
        (fn []
          (is (= #{[:ctx "ctx-a"]}
                 (set (keys (:states (mag-manip-fx/mag-manip-fx-snapshot)))))))))))

(deftest mag-manip-fx-runtime-required-without-binding-test
  (binding [mag-manip-fx/*mag-manip-fx-runtime* nil]
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"runtime is not bound"
          (mag-manip-fx/mag-manip-fx-snapshot)))))

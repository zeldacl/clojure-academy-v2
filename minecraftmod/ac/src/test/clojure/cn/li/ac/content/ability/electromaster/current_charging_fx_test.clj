(ns cn.li.ac.content.ability.electromaster.current-charging-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.hand-effects :as hand-effects]
            [cn.li.ac.content.ability.electromaster.current-charging-fx :as current-charging-fx]))

(defn- with-fresh-current-charging-fx-runtime [f]
  (hand-effects/call-with-hand-effect-runtime
    (hand-effects/create-hand-effect-runtime)
    (fn []
      (try
        (hand-effects/reset-hand-effect-registry-for-test!)
        (f)
        (finally
          (hand-effects/reset-hand-effect-registry-for-test!)
          (current-charging-fx/reset-current-charging-fx-for-test!))))))

(use-fixtures :each with-fresh-current-charging-fx-runtime)

(deftest init-registers-current-charging-fx-channels-test
  (let [registered-handler* (atom nil)
        registered-hand* (atom nil)]
    (with-redefs [hand-effects/register-hand-effect! (fn [effect-id effect-map]
                                                       (reset! registered-hand* [effect-id effect-map])
                                                       nil)
                  hand-effects/reset-hand-effect-state-for-test! (fn [& _] nil)
                  fx-registry/register-fx-channels! (fn [channels handler]
                                                      (reset! registered-handler* {:channels channels
                                                                                   :handler handler})
                                                      nil)]
      (current-charging-fx/init!)
      (is (= :current-charging (first @registered-hand*)))
      (is (= [:current-charging/fx-start
              :current-charging/fx-update
              :current-charging/fx-end]
             (:channels @registered-handler*)))
      (is (fn? (:handler @registered-handler*))))))

(deftest fx-handler-routes-through-hand-effects-test
  (let [handler* (atom nil)
        hand-enqueued* (atom [])]
    (with-redefs [hand-effects/register-hand-effect! (fn [& _] nil)
                  hand-effects/reset-hand-effect-state-for-test! (fn [& _] nil)
                  fx-registry/register-fx-channels! (fn [_ handler]
                                                      (reset! handler* handler)
                                                      nil)
                  hand-effects/enqueue-hand-effect! (fn [effect-id payload]
                                                      (swap! hand-enqueued* conj [effect-id payload])
                                                      nil)]
      (current-charging-fx/init!)
      (@handler* "ctx-1" :current-charging/fx-start {:is-item true})
      (@handler* "ctx-1" :current-charging/fx-update {:good? true :charge-ticks 20})
      (@handler* "ctx-1" :current-charging/fx-end {:is-item true})
      (is (= [[:current-charging {:is-item true :mode :start :ctx-id "ctx-1"}]
              [:current-charging {:good? true :charge-ticks 20 :mode :update :ctx-id "ctx-1"}]
              [:current-charging {:is-item true :mode :end :ctx-id "ctx-1"}]]
             @hand-enqueued*)))))

(deftest fx-state-updates-and-queues-loop-sound-test
  (let [queued* (atom [])
        enqueue-state! (var-get #'cn.li.ac.content.ability.electromaster.current-charging-fx/enqueue-state!)]
    (current-charging-fx/reset-current-charging-fx-for-test!)
    (with-redefs [client-sounds/queue-current-sound-effect! (fn [payload]
                                                              (swap! queued* conj payload)
                                                              nil)]
      (hand-effects/update-effect-state! :current-charging enqueue-state! {:ctx-id "ctx-1" :mode :start :is-item true})
      (is (true? (:active? (current-charging-fx/current-state [:ctx "ctx-1"]))))
      (is (true? (:is-item (current-charging-fx/current-state [:ctx "ctx-1"]))))
      (is (= 1 (count @queued*)))
      (hand-effects/update-effect-state! :current-charging enqueue-state!
        {:ctx-id "ctx-1"
         :mode :update
         :is-item true
         :good? true
         :charge-ticks 20
         :target {:x 1.0 :y 2.0 :z 3.0}
         :block-pos [1 2 3]
         :charged 4.0})
      (is (= 20 (:charge-ticks (current-charging-fx/current-state [:ctx "ctx-1"]))))
      (is (= 0.5 (:charge-ratio (current-charging-fx/current-state [:ctx "ctx-1"]))))
      (is (= {:x 1.0 :y 2.0 :z 3.0} (:target (current-charging-fx/current-state [:ctx "ctx-1"]))))
      (is (= [1 2 3] (:block-pos (current-charging-fx/current-state [:ctx "ctx-1"]))))
      (hand-effects/update-effect-state! :current-charging enqueue-state! {:ctx-id "ctx-1" :mode :end :is-item true})
      (is (false? (:active? (current-charging-fx/current-state [:ctx "ctx-1"]))))
      (is (true? (:blending? (current-charging-fx/current-state [:ctx "ctx-1"])))))))

(deftest two-owners-keep-current-charging-state-independent-test
  (let [queued* (atom [])
        enqueue-state! (var-get #'cn.li.ac.content.ability.electromaster.current-charging-fx/enqueue-state!)]
    (current-charging-fx/reset-current-charging-fx-for-test!)
    (with-redefs [client-sounds/queue-current-sound-effect! (fn [payload]
                                                              (swap! queued* conj payload)
                                                              nil)]
      (hand-effects/update-effect-state! :current-charging enqueue-state! {:ctx-id "ctx-a" :mode :start :is-item false})
      (hand-effects/update-effect-state! :current-charging enqueue-state! {:ctx-id "ctx-b" :mode :start :is-item true})
      (hand-effects/update-effect-state! :current-charging enqueue-state! {:ctx-id "ctx-a" :mode :update :good? true :charge-ticks 10})
      (hand-effects/update-effect-state! :current-charging enqueue-state! {:ctx-id "ctx-b" :mode :update :good? false :charge-ticks 30})
      (let [snapshot (current-charging-fx/current-charging-fx-snapshot)
            state-a (get (:states snapshot) [:ctx "ctx-a"])
            state-b (get (:states snapshot) [:ctx "ctx-b"])]
        (is (= #{[:ctx "ctx-a"] [:ctx "ctx-b"]}
               (set (keys (:states snapshot)))))
        (is (= 10 (:charge-ticks state-a)))
        (is (= 0.25 (:charge-ratio state-a)))
        (is (= 30 (:charge-ticks state-b)))
        (is (= 0.75 (:charge-ratio state-b))))
      (hand-effects/update-effect-state! :current-charging enqueue-state! {:ctx-id "ctx-a" :mode :end :is-item false})
      (is (false? (:active? (current-charging-fx/current-state [:ctx "ctx-a"]))))
      (is (true? (:blending? (current-charging-fx/current-state [:ctx "ctx-a"]))))
      (is (true? (:active? (current-charging-fx/current-state [:ctx "ctx-b"]))))
      (current-charging-fx/clear-current-charging-owner! [:ctx "ctx-a"])
      (let [snapshot (current-charging-fx/current-charging-fx-snapshot)]
        (is (nil? (get (:states snapshot) [:ctx "ctx-a"])))
        (is (= 30 (:charge-ticks (get (:states snapshot) [:ctx "ctx-b"]))))))
    (is (= 2 (count @queued*)))))

(deftest current-charging-fx-runtime-isolation-test
  (let [runtime-a (hand-effects/create-hand-effect-runtime)
        runtime-b (hand-effects/create-hand-effect-runtime)
        enqueue-state! (var-get #'cn.li.ac.content.ability.electromaster.current-charging-fx/enqueue-state!)]
    (with-redefs [client-sounds/queue-current-sound-effect! (fn [& _] nil)]
      (hand-effects/call-with-hand-effect-runtime
        runtime-a
        (fn []
          (hand-effects/update-effect-state! :current-charging enqueue-state! {:ctx-id "ctx-a" :mode :start :is-item true})
          (is (= #{[:ctx "ctx-a"]}
                 (set (keys (:states (current-charging-fx/current-charging-fx-snapshot))))))))
      (hand-effects/call-with-hand-effect-runtime
        runtime-b
        (fn []
          (is (= {:states {}}
                 (current-charging-fx/current-charging-fx-snapshot)))
          (hand-effects/update-effect-state! :current-charging enqueue-state! {:ctx-id "ctx-b" :mode :start :is-item false})
          (is (= #{[:ctx "ctx-b"]}
                 (set (keys (:states (current-charging-fx/current-charging-fx-snapshot))))))))
      (hand-effects/call-with-hand-effect-runtime
        runtime-a
        (fn []
          (is (= #{[:ctx "ctx-a"]}
                 (set (keys (:states (current-charging-fx/current-charging-fx-snapshot)))))))))))

(deftest current-charging-fx-snapshot-default-without-registered-state-test
  (is (= {:states {}}
         (current-charging-fx/current-charging-fx-snapshot)))
  (is (= false
         (:active? (current-charging-fx/current-state :missing-selector)))))

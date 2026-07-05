(ns cn.li.ac.content.ability.electromaster.current-charging-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.hand-effects :as hand-effects]
            [cn.li.ac.content.ability.electromaster.current-charging-fx :as current-charging-fx]))

(defn- invoke-hand-enqueue! [ctx-id channel payload]
  (let [enqueue-state! (var-get #'cn.li.ac.content.ability.electromaster.current-charging-fx/enqueue-state!)]
    (hand-effects/update-effect-state! :current-charging
      (fn [store] (enqueue-state! store ctx-id channel [:ctx ctx-id] payload)))))

(defn- with-fresh-current-charging-fx-runtime [f]
  (try
    (hand-effects/reset-hand-effect-registry-for-test!)
    (current-charging-fx/reset-current-charging-fx-for-test!)
    (current-charging-fx/init!)
    (f)
    (finally
      (hand-effects/reset-hand-effect-registry-for-test!)
      (current-charging-fx/reset-current-charging-fx-for-test!))))

(use-fixtures :each with-fresh-current-charging-fx-runtime)

(deftest init-registers-current-charging-fx-channels-test
  (let [registered-topics* (atom #{})
        registered-hand* (atom nil)]
    (with-redefs [hand-effects/register-hand-effect! (fn [effect-id effect-map]
                                                       (reset! registered-hand* [effect-id effect-map])
                                                       nil)
                  hand-effects/reset-hand-effect-state-for-test! (fn [& _] nil)
                  fx-registry/register-fx-channel! (fn [topic _handler]
                                                      (swap! registered-topics* conj topic)
                                                      nil)]
      (current-charging-fx/init!)
      (is (= :current-charging (first @registered-hand*)))
      (is (= #{:current-charging/fx-start
               :current-charging/fx-update
               :current-charging/fx-end}
             @registered-topics*)))))

(deftest fx-handler-routes-through-hand-effects-test
  (let [handlers* (atom {})
        hand-enqueued* (atom [])]
    (with-redefs [hand-effects/register-hand-effect! (fn [& _] nil)
                  hand-effects/reset-hand-effect-state-for-test! (fn [& _] nil)
                  fx-registry/register-fx-channel! (fn [topic handler]
                                                      (swap! handlers* assoc topic handler)
                                                      nil)
                  hand-effects/enqueue-hand-effect! (fn [effect-id ctx-id channel payload & opts]
                                                      (swap! hand-enqueued* conj (into [effect-id ctx-id channel payload] opts))
                                                      nil)]
      (current-charging-fx/init!)
      ((get @handlers* :current-charging/fx-start) "ctx-1" :current-charging/fx-start {:is-item true})
      ((get @handlers* :current-charging/fx-update) "ctx-1" :current-charging/fx-update {:good? true :charge-ticks 20})
      ((get @handlers* :current-charging/fx-end) "ctx-1" :current-charging/fx-end {:is-item true})
      (is (= [[:current-charging "ctx-1" :current-charging/fx-start {:mode :start :is-item true} :owner-key [:ctx "ctx-1"]]
              [:current-charging "ctx-1" :current-charging/fx-update {:mode :update :good? true :charge-ticks 20} :owner-key [:ctx "ctx-1"]]
              [:current-charging "ctx-1" :current-charging/fx-end {:mode :end :is-item true} :owner-key [:ctx "ctx-1"]]]
             @hand-enqueued*)))))

(deftest fx-state-updates-and-queues-loop-sound-test
  (let [queued* (atom [])]
    (current-charging-fx/reset-current-charging-fx-for-test!)
    (with-redefs [cn.li.ac.content.ability.electromaster.current-charging-fx/now-ms (fn [] 1000)
                  client-sounds/queue-current-sound-effect! (fn [payload]
                                                              (swap! queued* conj payload)
                                                              nil)]
      (invoke-hand-enqueue! "ctx-1" :current-charging/fx-start {:mode :start :is-item true})
      (is (true? (:active? (current-charging-fx/current-state [:ctx "ctx-1"]))))
      (is (true? (:is-item (current-charging-fx/current-state [:ctx "ctx-1"]))))
      (is (= 1 (count @queued*)))
      (invoke-hand-enqueue! "ctx-1" :current-charging/fx-update
        {:mode :update
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
      (invoke-hand-enqueue! "ctx-1" :current-charging/fx-end {:mode :end :is-item true})
      (is (false? (:active? (current-charging-fx/current-state [:ctx "ctx-1"]))))
      (is (true? (:blending? (current-charging-fx/current-state [:ctx "ctx-1"])))))))

(deftest two-owners-keep-current-charging-state-independent-test
  (let [queued* (atom [])]
    (current-charging-fx/reset-current-charging-fx-for-test!)
    (with-redefs [cn.li.ac.content.ability.electromaster.current-charging-fx/now-ms (fn [] 1000)
                  client-sounds/queue-current-sound-effect! (fn [payload]
                                                              (swap! queued* conj payload)
                                                              nil)]
      (invoke-hand-enqueue! "ctx-a" :current-charging/fx-start {:mode :start :is-item false})
      (invoke-hand-enqueue! "ctx-b" :current-charging/fx-start {:mode :start :is-item true})
      (invoke-hand-enqueue! "ctx-a" :current-charging/fx-update {:mode :update :good? true :charge-ticks 10})
      (invoke-hand-enqueue! "ctx-b" :current-charging/fx-update {:mode :update :good? false :charge-ticks 30})
      (let [snapshot (current-charging-fx/current-charging-fx-snapshot)
            state-a (get (:states snapshot) [:ctx "ctx-a"])
            state-b (get (:states snapshot) [:ctx "ctx-b"])]
        (is (= #{[:ctx "ctx-a"] [:ctx "ctx-b"]}
               (set (keys (:states snapshot)))))
        (is (= 10 (:charge-ticks state-a)))
        (is (= 0.25 (:charge-ratio state-a)))
        (is (= 30 (:charge-ticks state-b)))
        (is (= 0.75 (:charge-ratio state-b))))
      (invoke-hand-enqueue! "ctx-a" :current-charging/fx-end {:mode :end :is-item false})
      (is (false? (:active? (current-charging-fx/current-state [:ctx "ctx-a"]))))
      (is (true? (:blending? (current-charging-fx/current-state [:ctx "ctx-a"]))))
      (is (true? (:active? (current-charging-fx/current-state [:ctx "ctx-b"]))))
      (current-charging-fx/clear-current-charging-owner! [:ctx "ctx-a"])
      (let [snapshot (current-charging-fx/current-charging-fx-snapshot)]
        (is (nil? (get (:states snapshot) [:ctx "ctx-a"])))
        (is (= 30 (:charge-ticks (get (:states snapshot) [:ctx "ctx-b"]))))))
    (is (= 2 (count @queued*)))))

(deftest current-charging-fx-snapshot-default-without-registered-state-test
  (is (= {:states {}}
         (current-charging-fx/current-charging-fx-snapshot)))
  (is (= false
         (:active? (current-charging-fx/current-state :missing-selector)))))

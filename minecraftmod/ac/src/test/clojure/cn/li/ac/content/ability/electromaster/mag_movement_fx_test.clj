(ns cn.li.ac.content.ability.electromaster.mag-movement-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.content.ability.electromaster.mag-movement-fx :as mag-movement-fx]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(defn- reset-fixture [f]
  (runtime-hooks/with-client-ctx {:session-id :test-session}
    (level-effects/call-with-level-effect-runtime
      (level-effects/create-level-effect-runtime)
      (fn []
        (try
          (level-effects/reset-level-effect-registry-for-test!)
          (mag-movement-fx/reset-mag-movement-fx-for-test!)
          (client-sounds/poll-sound-effects!)
          (f)
          (finally
            (mag-movement-fx/reset-mag-movement-fx-for-test!)
            (client-sounds/poll-sound-effects!)
            (level-effects/reset-level-effect-registry-for-test!)))))))

(use-fixtures :each reset-fixture)

(defn- event [ctx-id payload]
  {:payload payload
   :ctx-id ctx-id
   :channel :mag-movement/fx-update
   :owner-key [:ctx ctx-id]})

(deftest init-registers-mag-movement-fx-channels-test
  (let [registered-effect* (atom nil)
        registered-topics* (atom #{})]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                          (reset! registered-effect* [effect-id effect-map])
                                                          nil)
                  fx-registry/register-fx-channel! (fn [topic _handler]
                                                      (swap! registered-topics* conj topic)
                                                      nil)]
      (mag-movement-fx/init!)
      (is (= :mag-movement (first @registered-effect*)))
      (is (= #{:mag-movement/fx-start :mag-movement/fx-update :mag-movement/fx-end}
             @registered-topics*)))))

(deftest fx-handler-routes-start-update-end-test
  (let [handlers* (atom {})
        enqueued* (atom [])]
    (with-redefs [level-effects/register-level-effect! (fn [& _] nil)
                  fx-registry/register-fx-channel! (fn [topic handler]
                                                      (swap! handlers* assoc topic handler)
                                                      nil)
                  level-effects/enqueue-level-effect! (fn [effect-id payload fx-context]
                                                        (swap! enqueued* conj [effect-id payload fx-context])
                                                        nil)]
      (mag-movement-fx/init!)
      ((get @handlers* :mag-movement/fx-start) "ctx" :mag-movement/fx-start {:target {:x 1.0 :y 2.0 :z 3.0}})
      ((get @handlers* :mag-movement/fx-update) "ctx" :mag-movement/fx-update {:target {:x 2.0 :y 3.0 :z 4.0}})
      ((get @handlers* :mag-movement/fx-end) "ctx" :mag-movement/fx-end {})
      (is (= 3 (count @enqueued*)))
      (let [[_effect-id payload fx-context] (first @enqueued*)]
        (is (= :mag-movement _effect-id))
        (is (= :start (:mode payload)))
        (is (= [:ctx "ctx"] (:owner-key payload)))
        (is (= "ctx" (:ctx-id payload)))
        (is (= :mag-movement/fx-start (:channel payload)))
        (is (= {:x 1.0 :y 2.0 :z 3.0} (:target payload)))
        (is (= "ctx" (:ctx-id fx-context)))
        (is (= :mag-movement/fx-start (:channel fx-context)))
        (is (= [:ctx "ctx"] (:owner-key fx-context))))
      (is (= :update (:mode (nth (second @enqueued*) 1))))
      (is (= :end (:mode (nth (nth @enqueued* 2) 1)))))))

(deftest enqueue-and-end-are-idempotent-test
  (let [enqueue-state! (var-get #'cn.li.ac.content.ability.electromaster.mag-movement-fx/enqueue-state!)]
    (level-effects/update-effect-state! :mag-movement enqueue-state!
      (event "ctx-main" {:mode :start :target {:x 1.0 :y 2.0 :z 3.0}}))
    (level-effects/update-effect-state! :mag-movement enqueue-state!
      (event "ctx-main" {:mode :update :target {:x 4.0 :y 5.0 :z 6.0}}))
    (is (= {:active? true :target {:x 4.0 :y 5.0 :z 6.0} :ticks 0}
           (select-keys (get (:effect-state (mag-movement-fx/mag-movement-fx-snapshot)) [:ctx "ctx-main"])
                        [:active? :target :ticks])))
    (level-effects/update-effect-state! :mag-movement enqueue-state! (event "ctx-main" {:mode :end}))
    (level-effects/update-effect-state! :mag-movement enqueue-state! (event "ctx-main" {:mode :end}))
    (is (nil? (get (:effect-state (mag-movement-fx/mag-movement-fx-snapshot)) [:ctx "ctx-main"])))))

(deftest tick-queues-loop-sound-every-10-ticks-test
  (let [enqueue-state! (var-get #'cn.li.ac.content.ability.electromaster.mag-movement-fx/enqueue-state!)
        sounds* (atom [])]
    (with-redefs [client-sounds/queue-sound-effect! (fn [_owner payload]
                              (swap! sounds* conj payload)
                                                      nil)]
      (level-effects/update-effect-state! :mag-movement enqueue-state!
        (event "ctx-main" {:mode :start :target {:x 1.0 :y 2.0 :z 3.0}}))
      (is (= 1 (count @sounds*)))
      (dotimes [_ 9]
        (level-effects/update-effect-state! :mag-movement (var-get #'cn.li.ac.content.ability.electromaster.mag-movement-fx/tick-state!)))
      (is (= 1 (count @sounds*)))
      (level-effects/update-effect-state! :mag-movement (var-get #'cn.li.ac.content.ability.electromaster.mag-movement-fx/tick-state!))
      (is (= 2 (count @sounds*)))
      (is (= "my_mod:em.move_loop" (:sound-id (second @sounds*)))))))

(deftest two-owners-keep-mag-movement-state-independent-test
  (let [enqueue-state! (var-get #'cn.li.ac.content.ability.electromaster.mag-movement-fx/enqueue-state!)]
    (level-effects/update-effect-state! :mag-movement enqueue-state!
      (event "ctx-a" {:mode :start :target {:x 1.0 :y 2.0 :z 3.0}}))
    (level-effects/update-effect-state! :mag-movement enqueue-state!
      (event "ctx-b" {:mode :start :target {:x 4.0 :y 5.0 :z 6.0}}))
    (level-effects/update-effect-state! :mag-movement enqueue-state!
      (event "ctx-a" {:mode :update :target {:x 7.0 :y 8.0 :z 9.0}}))
    (let [snapshot (mag-movement-fx/mag-movement-fx-snapshot)]
      (is (= {:x 7.0 :y 8.0 :z 9.0}
             (:target (get (:effect-state snapshot) [:ctx "ctx-a"]))))
      (is (= {:x 4.0 :y 5.0 :z 6.0}
             (:target (get (:effect-state snapshot) [:ctx "ctx-b"]))))
      (mag-movement-fx/clear-mag-movement-owner! [:ctx "ctx-a"])
      (let [after-clear (mag-movement-fx/mag-movement-fx-snapshot)]
        (is (nil? (get (:effect-state after-clear) [:ctx "ctx-a"])))
        (is (some? (get (:effect-state after-clear) [:ctx "ctx-b"])))))))

(deftest mag-movement-fx-snapshot-default-without-registered-state-test
  (is (= {:effect-state {}}
         (mag-movement-fx/mag-movement-fx-snapshot))))

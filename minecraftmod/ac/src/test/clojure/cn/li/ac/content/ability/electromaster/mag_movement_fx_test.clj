(ns cn.li.ac.content.ability.electromaster.mag-movement-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.content.ability.electromaster.mag-movement-fx :as mag-movement-fx]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(defn- invoke-level-enqueue! [ctx-id channel payload]
  (arc-beam/enqueue-for-test! :mag-movement ctx-id channel payload))

(defn- invoke-tick! []
  (level-effects/update-effect-state! :mag-movement
    (fn [store] (arc-beam/effect-tick-state! :level :mag-movement store))))

(defn- reset-fixture [f]
  (runtime-hooks/with-client-ctx-fn {:session-id :test-session} (fn [] (try
      (level-effects/reset-level-effect-registry-for-test!)
      (mag-movement-fx/reset-fx-for-test!)
      (mag-movement-fx/init!)
      (client-sounds/poll-sound-effects!)
      (f)
      (finally
        (mag-movement-fx/reset-fx-for-test!)
        (client-sounds/poll-sound-effects!)
        (level-effects/reset-level-effect-registry-for-test!))))))

(use-fixtures :each reset-fixture)

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
                  level-effects/enqueue-level-effect! (fn [effect-id ctx-id channel payload & opts]
                                                        (swap! enqueued* conj (into [effect-id ctx-id channel payload] opts))
                                                        nil)]
      (mag-movement-fx/init!)
      ((get @handlers* :mag-movement/fx-start) "ctx" :mag-movement/fx-start {:target {:x 1.0 :y 2.0 :z 3.0}})
      ((get @handlers* :mag-movement/fx-update) "ctx" :mag-movement/fx-update {:target {:x 2.0 :y 3.0 :z 4.0}})
      ((get @handlers* :mag-movement/fx-end) "ctx" :mag-movement/fx-end {})
      (is (= 3 (count @enqueued*)))
      (let [[effect-id ctx-id channel payload & opts] (first @enqueued*)]
        (is (= :mag-movement effect-id))
        (is (= :start (:mode payload)))
        (is (= {:x 1.0 :y 2.0 :z 3.0} (:target payload)))
        (is (= "ctx" ctx-id))
        (is (= :mag-movement/fx-start channel))
        (is (= [:owner-key [:ctx "ctx"]] opts)))
      (is (= :update (:mode (nth (second @enqueued*) 3))))
      (is (= :end (:mode (nth (nth @enqueued* 2) 3)))))))

(deftest enqueue-and-end-are-idempotent-test
  (invoke-level-enqueue! "ctx-main" :mag-movement/fx-start {:mode :start :target {:x 1.0 :y 2.0 :z 3.0}})
  (invoke-level-enqueue! "ctx-main" :mag-movement/fx-update {:mode :update :target {:x 4.0 :y 5.0 :z 6.0}})
  (is (= {:active? true :target {:x 4.0 :y 5.0 :z 6.0} :ticks 0}
         (select-keys (get (:effect-state (mag-movement-fx/fx-snapshot)) [:ctx "ctx-main"])
                      [:active? :target :ticks])))
  (invoke-level-enqueue! "ctx-main" :mag-movement/fx-end {:mode :end})
  (invoke-level-enqueue! "ctx-main" :mag-movement/fx-end {:mode :end})
  (is (nil? (get (:effect-state (mag-movement-fx/fx-snapshot)) [:ctx "ctx-main"]))))

(deftest tick-queues-loop-sound-every-10-ticks-test
  (let [sounds* (atom [])]
    (with-redefs [client-sounds/queue-sound-effect! (fn [_owner payload]
                                                      (swap! sounds* conj payload)
                                                      nil)]
      (invoke-level-enqueue! "ctx-main" :mag-movement/fx-start {:mode :start :target {:x 1.0 :y 2.0 :z 3.0}})
      (is (= 1 (count @sounds*)))
      (dotimes [_ 9]
        (invoke-tick!))
      (is (= 1 (count @sounds*)))
      (invoke-tick!)
      (is (= 2 (count @sounds*)))
      (is (= "my_mod:em.move_loop" (:sound-id (second @sounds*)))))))

(deftest two-owners-keep-mag-movement-state-independent-test
  (invoke-level-enqueue! "ctx-a" :mag-movement/fx-start {:mode :start :target {:x 1.0 :y 2.0 :z 3.0}})
  (invoke-level-enqueue! "ctx-b" :mag-movement/fx-start {:mode :start :target {:x 4.0 :y 5.0 :z 6.0}})
  (invoke-level-enqueue! "ctx-a" :mag-movement/fx-update {:mode :update :target {:x 7.0 :y 8.0 :z 9.0}})
  (let [snapshot (mag-movement-fx/fx-snapshot)]
    (is (= {:x 7.0 :y 8.0 :z 9.0}
           (:target (get (:effect-state snapshot) [:ctx "ctx-a"]))))
    (is (= {:x 4.0 :y 5.0 :z 6.0}
           (:target (get (:effect-state snapshot) [:ctx "ctx-b"]))))
    (mag-movement-fx/clear-fx-owner! [:ctx "ctx-a"])
    (let [after-clear (mag-movement-fx/fx-snapshot)]
      (is (nil? (get (:effect-state after-clear) [:ctx "ctx-a"])))
      (is (some? (get (:effect-state after-clear) [:ctx "ctx-b"]))))))

(deftest fx-snapshot-default-without-registered-state-test
  (is (= {:effect-state {}}
         (mag-movement-fx/fx-snapshot))))

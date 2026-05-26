(ns cn.li.ac.content.ability.electromaster.mag-movement-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.content.ability.electromaster.mag-movement-fx :as mag-movement-fx]))

(defn- reset-fixture [f]
  (mag-movement-fx/reset-mag-movement-fx-for-test!)
  (client-sounds/poll-sound-effects!)
  (f)
  (mag-movement-fx/reset-mag-movement-fx-for-test!)
  (client-sounds/poll-sound-effects!))

(defn- event [ctx-id payload]
  {:payload payload
   :ctx-id ctx-id
   :channel :mag-movement/fx-update
   :owner-key [:ctx ctx-id]})

(use-fixtures :each reset-fixture)

(deftest init-registers-mag-movement-fx-channels-test
  (let [registered-effect* (atom nil)
        registered-handler* (atom nil)]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                          (reset! registered-effect* [effect-id effect-map])
                                                          nil)
                  fx-registry/register-fx-channels! (fn [channels handler]
                                                      (reset! registered-handler* {:channels channels
                                                                                   :handler handler})
                                                      nil)]
      (mag-movement-fx/init!)
      (is (= :mag-movement (first @registered-effect*)))
      (is (= #{:mag-movement/fx-start :mag-movement/fx-update :mag-movement/fx-end}
             (set (:channels @registered-handler*)))))))

(deftest fx-handler-routes-start-update-end-test
  (let [handler* (atom nil)
        enqueued* (atom [])]
    (with-redefs [level-effects/register-level-effect! (fn [& _] nil)
                  fx-registry/register-fx-channels! (fn [_ handler]
                                                      (reset! handler* handler)
                                                      nil)
                  level-effects/enqueue-level-effect! (fn [effect-id payload fx-context]
                                                        (swap! enqueued* conj [effect-id payload fx-context])
                                                        nil)]
      (mag-movement-fx/init!)
      (@handler* "ctx" :mag-movement/fx-start {:target {:x 1.0 :y 2.0 :z 3.0}})
      (@handler* "ctx" :mag-movement/fx-update {:target {:x 2.0 :y 3.0 :z 4.0}})
      (@handler* "ctx" :mag-movement/fx-end {})
      (is (= [[:mag-movement {:target {:x 1.0 :y 2.0 :z 3.0} :mode :start}
           {:ctx-id "ctx" :channel :mag-movement/fx-start}]
          [:mag-movement {:target {:x 2.0 :y 3.0 :z 4.0} :mode :update}
           {:ctx-id "ctx" :channel :mag-movement/fx-update}]
          [:mag-movement {:mode :end}
           {:ctx-id "ctx" :channel :mag-movement/fx-end}]]
             @enqueued*)))))

(deftest enqueue-and-end-are-idempotent-test
  (let [enqueue! @#'cn.li.ac.content.ability.electromaster.mag-movement-fx/enqueue!]
    (enqueue! (event "ctx-main" {:mode :start :target {:x 1.0 :y 2.0 :z 3.0}}))
    (enqueue! (event "ctx-main" {:mode :update :target {:x 4.0 :y 5.0 :z 6.0}}))
    (is (= {:active? true :target {:x 4.0 :y 5.0 :z 6.0} :ticks 0}
      (select-keys (get (:effect-state (mag-movement-fx/mag-movement-fx-snapshot)) [:ctx "ctx-main"])
         [:active? :target :ticks])))
    (enqueue! (event "ctx-main" {:mode :end}))
    (enqueue! (event "ctx-main" {:mode :end}))
    (is (nil? (get (:effect-state (mag-movement-fx/mag-movement-fx-snapshot)) [:ctx "ctx-main"])))))

(deftest tick-queues-loop-sound-every-10-ticks-test
  (let [enqueue! @#'cn.li.ac.content.ability.electromaster.mag-movement-fx/enqueue!
        tick! @#'cn.li.ac.content.ability.electromaster.mag-movement-fx/tick!]
    (enqueue! (event "ctx-main" {:mode :start :target {:x 1.0 :y 2.0 :z 3.0}}))
    (let [after-start (client-sounds/poll-sound-effects!)]
      (is (= 1 (count after-start))))
    (dotimes [_ 9] (tick!))
    (is (empty? (client-sounds/poll-sound-effects!)))
    (tick!)
    (let [loop-sounds (client-sounds/poll-sound-effects!)]
      (is (= 1 (count loop-sounds)))
      (is (= "my_mod:em.move_loop" (:sound-id (first loop-sounds)))))))

(deftest two-owners-keep-mag-movement-state-independent-test
  (let [enqueue! @#'cn.li.ac.content.ability.electromaster.mag-movement-fx/enqueue!]
    (enqueue! (event "ctx-a" {:mode :start :target {:x 1.0 :y 2.0 :z 3.0}}))
    (enqueue! (event "ctx-b" {:mode :start :target {:x 4.0 :y 5.0 :z 6.0}}))
    (enqueue! (event "ctx-a" {:mode :update :target {:x 7.0 :y 8.0 :z 9.0}}))
    (let [snapshot (mag-movement-fx/mag-movement-fx-snapshot)]
      (is (= {:x 7.0 :y 8.0 :z 9.0}
             (:target (get (:effect-state snapshot) [:ctx "ctx-a"]))))
      (is (= {:x 4.0 :y 5.0 :z 6.0}
             (:target (get (:effect-state snapshot) [:ctx "ctx-b"])))))
    (mag-movement-fx/clear-mag-movement-owner! [:ctx "ctx-a"])
    (let [snapshot (mag-movement-fx/mag-movement-fx-snapshot)]
      (is (nil? (get (:effect-state snapshot) [:ctx "ctx-a"])))
      (is (some? (get (:effect-state snapshot) [:ctx "ctx-b"]))))))

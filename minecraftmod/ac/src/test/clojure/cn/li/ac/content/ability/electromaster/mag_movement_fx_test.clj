(ns cn.li.ac.content.ability.electromaster.mag-movement-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.content.ability.electromaster.mag-movement-fx :as mag-movement-fx]))

(defn- reset-fixture [f]
  (reset! @#'cn.li.ac.content.ability.electromaster.mag-movement-fx/effect-state nil)
  (client-sounds/poll-sound-effects!)
  (f)
  (reset! @#'cn.li.ac.content.ability.electromaster.mag-movement-fx/effect-state nil)
  (client-sounds/poll-sound-effects!))

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
                  level-effects/enqueue-level-effect! (fn [effect-id payload]
                                                        (swap! enqueued* conj [effect-id payload])
                                                        nil)]
      (mag-movement-fx/init!)
      (@handler* "ctx" :mag-movement/fx-start {:target {:x 1.0 :y 2.0 :z 3.0}})
      (@handler* "ctx" :mag-movement/fx-update {:target {:x 2.0 :y 3.0 :z 4.0}})
      (@handler* "ctx" :mag-movement/fx-end {})
      (is (= [[:mag-movement {:target {:x 1.0 :y 2.0 :z 3.0} :mode :start}]
              [:mag-movement {:target {:x 2.0 :y 3.0 :z 4.0} :mode :update}]
              [:mag-movement {:mode :end}]]
             @enqueued*)))))

(deftest enqueue-and-end-are-idempotent-test
  (let [enqueue! @#'cn.li.ac.content.ability.electromaster.mag-movement-fx/enqueue!]
    (enqueue! {:mode :start :target {:x 1.0 :y 2.0 :z 3.0}})
    (enqueue! {:mode :update :target {:x 4.0 :y 5.0 :z 6.0}})
    (is (= {:active? true :target {:x 4.0 :y 5.0 :z 6.0} :ticks 0}
           @@#'cn.li.ac.content.ability.electromaster.mag-movement-fx/effect-state))
    (enqueue! {:mode :end})
    (enqueue! {:mode :end})
    (is (nil? @@#'cn.li.ac.content.ability.electromaster.mag-movement-fx/effect-state))))

(deftest tick-queues-loop-sound-every-10-ticks-test
  (let [enqueue! @#'cn.li.ac.content.ability.electromaster.mag-movement-fx/enqueue!
        tick! @#'cn.li.ac.content.ability.electromaster.mag-movement-fx/tick!]
    (enqueue! {:mode :start :target {:x 1.0 :y 2.0 :z 3.0}})
    (let [after-start (client-sounds/poll-sound-effects!)]
      (is (= 1 (count after-start))))
    (dotimes [_ 9] (tick!))
    (is (empty? (client-sounds/poll-sound-effects!)))
    (tick!)
    (let [loop-sounds (client-sounds/poll-sound-effects!)]
      (is (= 1 (count loop-sounds)))
      (is (= "my_mod:em.move_loop" (:sound-id (first loop-sounds)))))))

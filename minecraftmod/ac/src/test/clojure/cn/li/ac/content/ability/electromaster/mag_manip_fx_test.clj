(ns cn.li.ac.content.ability.electromaster.mag-manip-fx-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.hand-effects :as hand-effects]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.content.ability.electromaster.mag-manip-fx :as mag-manip-fx]))

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
                  level-effects/enqueue-level-effect! (fn [effect-id payload]
                                                        (swap! level-enqueued* conj [effect-id payload])
                                                      nil)]
      (mag-manip-fx/init!)
      (@handler* "ctx-1" :mag-manip/fx-hold {:mode :hold-start :block-id "minecraft:iron_block"})
      (@handler* "ctx-1" :mag-manip/fx-hold {:mode :hold-loop :block-id "minecraft:iron_block"})
      (@handler* "ctx-1" :mag-manip/fx-throw {:start {:x 0.0 :y 0.0 :z 0.0}
                                               :end {:x 0.0 :y 0.0 :z 5.0}})
      (@handler* "ctx-1" :mag-manip/fx-end {:mode :end :reason :performed})

      (is (= [[:mag-manip {:mode :hold-start :block-id "minecraft:iron_block"}]
              [:mag-manip {:mode :hold-loop :block-id "minecraft:iron_block"}]
              [:mag-manip {:start {:x 0.0 :y 0.0 :z 0.0}
                           :end {:x 0.0 :y 0.0 :z 5.0}
                           :mode :throw}]
              [:mag-manip {:mode :end :reason :performed}]]
             @hand-enqueued*))
            (is (= @hand-enqueued* @level-enqueued*)))))

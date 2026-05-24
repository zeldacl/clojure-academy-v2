(ns cn.li.ac.content.ability.electromaster.arc-gen-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.content.ability.electromaster.arc-gen-fx :as arc-fx]))

(defn- reset-fixture [f]
  (reset! (var-get #'cn.li.ac.content.ability.electromaster.arc-gen-fx/arcs*) [])
  (f)
  (reset! (var-get #'cn.li.ac.content.ability.electromaster.arc-gen-fx/arcs*) []))

(use-fixtures :each reset-fixture)

(deftest init-registers-arc-gen-fx-channel-test
  (let [registered-level* (atom nil)
        registered-handler* (atom nil)]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                         (reset! registered-level* [effect-id effect-map])
                                                         nil)
                  fx-registry/register-fx-channel! (fn [channel handler]
                                                     (reset! registered-handler* {:channel channel
                                                                                  :handler handler})
                                                     nil)]
      (arc-fx/init!)
      (is (= :arc-gen (first @registered-level*)))
      (is (= :arc-gen/fx-perform (:channel @registered-handler*))))))

(deftest fx-handler-routes-perform-payload-test
  (let [handler* (atom nil)
        enqueued* (atom [])]
    (with-redefs [level-effects/register-level-effect! (fn [& _] nil)
                  fx-registry/register-fx-channel! (fn [_ handler]
                                                     (reset! handler* handler)
                                                     nil)
                  level-effects/enqueue-level-effect! (fn [effect-id payload]
                                                        (swap! enqueued* conj [effect-id payload])
                                                        nil)]
      (arc-fx/init!)
      (@handler* "ctx-arc" :arc-gen/fx-perform {:start {:x 1.0 :y 2.0 :z 3.0}
                                                 :end {:x 4.0 :y 5.0 :z 6.0}
                                                 :hit-type :entity})
      (is (= [[:arc-gen {:mode :perform
                         :start {:x 1.0 :y 2.0 :z 3.0}
                         :end {:x 4.0 :y 5.0 :z 6.0}
                         :hit-type :entity}]]
             @enqueued*)))))

(deftest enqueue-perform-adds-arc-and-plays-sound-test
  (let [enqueue! (var-get #'cn.li.ac.content.ability.electromaster.arc-gen-fx/enqueue!)
        build-plan (var-get #'cn.li.ac.content.ability.electromaster.arc-gen-fx/build-plan)
        sounds* (atom [])]
    (with-redefs [client-sounds/queue-sound-effect! (fn [payload]
                                                       (swap! sounds* conj payload)
                                                       nil)]
      (enqueue! {:mode :perform
                 :start {:x 0.0 :y 64.0 :z 0.0}
                 :end {:x 3.0 :y 64.0 :z 3.0}
                 :hit-type :block})
      (let [plan (build-plan {:x 0.0 :y 65.0 :z 0.0} nil 0)]
        (is (some? plan))
        (is (seq (:ops plan))))
      (is (= 1 (count @sounds*)))
      (is (= "my_mod:em.arc_weak" (:sound-id (first @sounds*)))))))

(ns cn.li.ac.content.ability.electromaster.thunder-bolt-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.content.ability.electromaster.thunder-bolt-fx :as tb-fx]))

(defn- reset-fixture [f]
  (reset! (var-get #'cn.li.ac.content.ability.electromaster.thunder-bolt-fx/arcs) [])
  (f)
  (reset! (var-get #'cn.li.ac.content.ability.electromaster.thunder-bolt-fx/arcs) []))

(use-fixtures :each reset-fixture)

(deftest init-registers-thunder-bolt-fx-test
  (let [registered-level* (atom nil)
        registered-handler* (atom nil)]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                         (reset! registered-level* [effect-id effect-map])
                                                         nil)
                  fx-registry/register-fx-channel! (fn [channel handler]
                                                     (reset! registered-handler* {:channel channel
                                                                                  :handler handler})
                                                     nil)]
      (tb-fx/init!)
      (is (= :thunder-bolt-strike (first @registered-level*)))
      (is (= :thunder-bolt/fx-perform (:channel @registered-handler*))))))

(deftest fx-handler-routes-payload-to-level-effect-test
  (let [handler* (atom nil)
        enqueued* (atom [])]
    (with-redefs [level-effects/register-level-effect! (fn [& _] nil)
                  fx-registry/register-fx-channel! (fn [_channel handler]
                                                     (reset! handler* handler)
                                                     nil)
                  level-effects/enqueue-level-effect! (fn [effect-id payload]
                                                        (swap! enqueued* conj [effect-id payload])
                                                        nil)]
      (tb-fx/init!)
      (@handler* "ctx-1" :thunder-bolt/fx-perform {:start {:x 0.0 :y 64.0 :z 0.0}
                                                     :end {:x 1.0 :y 65.0 :z 1.0}
                                                     :aoe-points [{:x 2.0 :y 65.0 :z 1.0}]})
      (is (= [[:thunder-bolt-strike
               {:start {:x 0.0 :y 64.0 :z 0.0}
                :end {:x 1.0 :y 65.0 :z 1.0}
                :aoe-points [{:x 2.0 :y 65.0 :z 1.0}]}]]
             @enqueued*)))))

(deftest enqueue-main-and-aoe-arcs-tick-and-build-plan-test
  (let [enqueue! (var-get #'cn.li.ac.content.ability.electromaster.thunder-bolt-fx/enqueue!)
        tick! (var-get #'cn.li.ac.content.ability.electromaster.thunder-bolt-fx/tick!)
        build-plan (var-get #'cn.li.ac.content.ability.electromaster.thunder-bolt-fx/build-plan)
        sounds* (atom [])]
    (with-redefs [client-sounds/queue-sound-effect! (fn [payload]
                                                       (swap! sounds* conj payload)
                                                       nil)
                  rand-int (fn [_] 0)]
      (enqueue! {:start {:x 0.0 :y 64.0 :z 0.0}
                 :end {:x 3.0 :y 64.0 :z 3.0}
                 :aoe-points [{:x 4.0 :y 64.0 :z 2.0}
                              {:x 2.0 :y 64.0 :z 4.0}]})
      (is (= 5 (count @(var-get #'cn.li.ac.content.ability.electromaster.thunder-bolt-fx/arcs))))
      (is (= 1 (count @sounds*)))
      (is (= "my_mod:em.arc_strong" (:sound-id (first @sounds*))))
      (is (some? (build-plan {:x 0.0 :y 65.0 :z 0.0} nil 0)))
      (dotimes [_ 30]
        (tick!))
      (is (nil? (build-plan {:x 0.0 :y 65.0 :z 0.0} nil 0))))))

(deftest enqueue-ignores-invalid-payload-test
  (let [enqueue! (var-get #'cn.li.ac.content.ability.electromaster.thunder-bolt-fx/enqueue!)
        build-plan (var-get #'cn.li.ac.content.ability.electromaster.thunder-bolt-fx/build-plan)
        sounds* (atom [])]
    (with-redefs [client-sounds/queue-sound-effect! (fn [payload]
                                                       (swap! sounds* conj payload)
                                                       nil)]
      (enqueue! {:start {:x 0.0 :y 64.0 :z 0.0}})
      (is (empty? @(var-get #'cn.li.ac.content.ability.electromaster.thunder-bolt-fx/arcs)))
      (is (empty? @sounds*))
      (is (nil? (build-plan {:x 0.0 :y 65.0 :z 0.0} nil 0))))))

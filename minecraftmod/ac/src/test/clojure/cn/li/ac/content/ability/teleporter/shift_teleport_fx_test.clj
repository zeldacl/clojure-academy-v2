(ns cn.li.ac.content.ability.teleporter.shift-teleport-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.content.ability.teleporter.shift-teleport-fx :as stfx]))

(defn- reset-fixture [f]
  (reset! (var-get #'cn.li.ac.content.ability.teleporter.shift-teleport-fx/fx-state) nil)
  (f)
  (reset! (var-get #'cn.li.ac.content.ability.teleporter.shift-teleport-fx/fx-state) nil))

(use-fixtures :each reset-fixture)

(deftest init-registers-shift-teleport-fx-channels-test
  (let [registered-level* (atom nil)
        registered-handler* (atom nil)]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                         (reset! registered-level* [effect-id effect-map])
                                                         nil)
                  fx-registry/register-fx-channels! (fn [channels handler]
                                                      (reset! registered-handler* {:channels channels
                                                                                   :handler handler})
                                                      nil)]
      (stfx/init!)
      (is (= :shift-teleport (first @registered-level*)))
      (is (= #{:shift-tp/fx-start
               :shift-tp/fx-update
               :shift-tp/fx-perform
               :shift-tp/fx-end}
             (set (:channels @registered-handler*)))))))

(deftest fx-handler-routes-update-and-perform-payloads-test
  (let [handler* (atom nil)
        enqueued* (atom [])]
    (with-redefs [level-effects/register-level-effect! (fn [& _] nil)
                  fx-registry/register-fx-channels! (fn [_ handler]
                                                      (reset! handler* handler)
                                                      nil)
                  level-effects/enqueue-level-effect! (fn [effect-id payload]
                                                        (swap! enqueued* conj [effect-id payload])
                                                        nil)]
      (stfx/init!)
      (@handler* "ctx-1" :shift-tp/fx-start nil)
      (@handler* "ctx-1" :shift-tp/fx-update {:x 3.0 :y 4.0 :z 5.0 :target-count 2 :target-hit? true :hand-valid? true})
      (@handler* "ctx-1" :shift-tp/fx-perform {:from-x 1.0 :from-y 2.0 :from-z 3.0 :x 6.0 :y 7.0 :z 8.0})
      (@handler* "ctx-1" :shift-tp/fx-end nil)
      (is (= [[:shift-teleport {:mode :start}]
              [:shift-teleport {:mode :update
                                :x 3.0 :y 4.0 :z 5.0
                                :target-count 2 :target-hit? true :hand-valid? true}]
              [:shift-teleport {:mode :perform
                                :x 6.0 :y 7.0 :z 8.0
                                :from-x 1.0 :from-y 2.0 :from-z 3.0}]
              [:shift-teleport {:mode :end}]]
             @enqueued*)))))

(deftest enqueue-perform-emits-path-particles-and-sound-test
  (let [particles* (atom [])
        sounds* (atom [])
        enqueue! (var-get #'cn.li.ac.content.ability.teleporter.shift-teleport-fx/enqueue!)]
    (with-redefs [client-particles/queue-particle-effect! (fn [payload]
                                                             (swap! particles* conj payload)
                                                             nil)
                  client-sounds/queue-sound-effect! (fn [payload]
                                                      (swap! sounds* conj payload)
                                                      nil)]
      (enqueue! {:mode :perform
                 :from-x 0.0 :from-y 64.0 :from-z 0.0
                 :x 5.0 :y 64.0 :z 0.0})
      (is (>= (count @particles*) 2))
      (is (= 1 (count @sounds*)))
      (is (= "my_mod:tp.tp" (:sound-id (first @sounds*)))))))

(deftest enqueue-end-clears-state-test
  (let [enqueue! (var-get #'cn.li.ac.content.ability.teleporter.shift-teleport-fx/enqueue!)]
    (enqueue! {:mode :start})
    (enqueue! {:mode :update :x 1.0 :y 2.0 :z 3.0 :target-count 1 :target-hit? false :hand-valid? true})
    (is (some? @(var-get #'cn.li.ac.content.ability.teleporter.shift-teleport-fx/fx-state)))
    (enqueue! {:mode :end})
    (is (nil? @(var-get #'cn.li.ac.content.ability.teleporter.shift-teleport-fx/fx-state)))))

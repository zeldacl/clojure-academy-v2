(ns cn.li.ac.content.ability.teleporter.flashing-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.content.ability.teleporter.flashing-fx :as ffx]))

(defn- reset-fixture [f]
  (reset! (var-get #'cn.li.ac.content.ability.teleporter.flashing-fx/fx-state) nil)
  (f)
  (reset! (var-get #'cn.li.ac.content.ability.teleporter.flashing-fx/fx-state) nil))

(use-fixtures :each reset-fixture)

(deftest init-registers-flashing-fx-channels-test
  (let [registered-level* (atom nil)
        registered-handler* (atom nil)]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                         (reset! registered-level* [effect-id effect-map])
                                                         nil)
                  fx-registry/register-fx-channels! (fn [channels handler]
                                                      (reset! registered-handler* {:channels channels
                                                                                   :handler handler})
                                                      nil)]
      (ffx/init!)
      (is (= :flashing (first @registered-level*)))
      (is (= #{:flashing/fx-state-start
               :flashing/fx-preview-start
               :flashing/fx-preview-update
               :flashing/fx-preview-end
               :flashing/fx-perform
               :flashing/fx-state-end}
             (set (:channels @registered-handler*)))))))

(deftest fx-handler-routes-preview-and-perform-events-test
  (let [handler* (atom nil)
        enqueued* (atom [])]
    (with-redefs [level-effects/register-level-effect! (fn [& _] nil)
                  fx-registry/register-fx-channels! (fn [_ handler]
                                                      (reset! handler* handler)
                                                      nil)
                  level-effects/enqueue-level-effect! (fn [effect-id payload]
                                                        (swap! enqueued* conj [effect-id payload])
                                                        nil)]
      (ffx/init!)
      (@handler* "ctx-1" :flashing/fx-state-start nil)
      (@handler* "ctx-1" :flashing/fx-preview-start {:to-x 1.0 :to-y 64.0 :to-z 2.0})
      (@handler* "ctx-1" :flashing/fx-preview-update {:to-x 2.0 :to-y 64.0 :to-z 3.0})
      (@handler* "ctx-1" :flashing/fx-perform {:from-x 0.0 :from-y 64.0 :from-z 0.0
                                                 :to-x 2.0 :to-y 64.0 :to-z 3.0})
      (@handler* "ctx-1" :flashing/fx-preview-end nil)
      (@handler* "ctx-1" :flashing/fx-state-end nil)
      (is (= [[:flashing {:mode :state-start}]
              [:flashing {:mode :preview-start :to-x 1.0 :to-y 64.0 :to-z 2.0}]
              [:flashing {:mode :preview-update :to-x 2.0 :to-y 64.0 :to-z 3.0}]
              [:flashing {:mode :perform
                          :from-x 0.0 :from-y 64.0 :from-z 0.0
                          :to-x 2.0 :to-y 64.0 :to-z 3.0}]
              [:flashing {:mode :preview-end}]
              [:flashing {:mode :state-end}]]
             @enqueued*)))))

(deftest enqueue-perform-emits-sound-and-particles-test
  (let [particles* (atom [])
        sounds* (atom [])
        enqueue! (var-get #'cn.li.ac.content.ability.teleporter.flashing-fx/enqueue!)
        tick! (var-get #'cn.li.ac.content.ability.teleporter.flashing-fx/tick!)]
    (with-redefs [client-particles/queue-particle-effect! (fn [payload]
                                                             (swap! particles* conj payload)
                                                             nil)
                  client-sounds/queue-sound-effect! (fn [payload]
                                                      (swap! sounds* conj payload)
                                                      nil)]
      (enqueue! {:mode :state-start})
      (enqueue! {:mode :preview-update :to-x 1.0 :to-y 64.0 :to-z 1.0})
      (enqueue! {:mode :perform
                 :from-x 0.0 :from-y 64.0 :from-z 0.0
                 :to-x 2.0 :to-y 64.0 :to-z 2.0})
      (tick!)
      (is (= 1 (count @sounds*)))
      (is (>= (count @particles*) 2))
      (is (= "my_mod:tp.tp_flashing" (:sound-id (first @sounds*)))))))

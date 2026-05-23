(ns cn.li.ac.content.ability.teleporter.flesh-ripping-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.content.ability.teleporter.flesh-ripping-fx :as frfx]))

(defn- reset-fixture [f]
  (reset! (var-get #'cn.li.ac.content.ability.teleporter.flesh-ripping-fx/fx-state) nil)
  (f)
  (reset! (var-get #'cn.li.ac.content.ability.teleporter.flesh-ripping-fx/fx-state) nil))

(use-fixtures :each reset-fixture)

(deftest init-registers-flesh-ripping-fx-channels-test
  (let [registered-level* (atom nil)
        registered-handler* (atom nil)]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                         (reset! registered-level* [effect-id effect-map])
                                                         nil)
                  fx-registry/register-fx-channels! (fn [channels handler]
                                                      (reset! registered-handler* {:channels channels
                                                                                   :handler handler})
                                                      nil)]
      (frfx/init!)
      (is (= :flesh-ripping (first @registered-level*)))
      (is (= #{:flesh-ripping/fx-start
               :flesh-ripping/fx-update
               :flesh-ripping/fx-perform
               :flesh-ripping/fx-end}
             (set (:channels @registered-handler*)))))))

(deftest fx-handler-routes-start-update-perform-end-payloads-test
  (let [handler* (atom nil)
        enqueued* (atom [])]
    (with-redefs [level-effects/register-level-effect! (fn [& _] nil)
                  fx-registry/register-fx-channels! (fn [_ handler]
                                                      (reset! handler* handler)
                                                      nil)
                  level-effects/enqueue-level-effect! (fn [effect-id payload]
                                                        (swap! enqueued* conj [effect-id payload])
                                                        nil)]
      (frfx/init!)
      (@handler* "ctx-1" :flesh-ripping/fx-start nil)
      (@handler* "ctx-1" :flesh-ripping/fx-update {:target-x 1.0 :target-y 2.0 :target-z 3.0 :hit? true :target-uuid "target-1"})
      (@handler* "ctx-1" :flesh-ripping/fx-perform {:target-x 4.0 :target-y 5.0 :target-z 6.0 :hit? true :target-uuid "target-2"})
      (@handler* "ctx-1" :flesh-ripping/fx-end nil)

      (is (= [[:flesh-ripping {:mode :start}]
              [:flesh-ripping {:mode :update
                               :target-x 1.0
                               :target-y 2.0
                               :target-z 3.0
                               :hit? true
                               :target-uuid "target-1"}]
              [:flesh-ripping {:mode :perform
                               :target-x 4.0
                               :target-y 5.0
                               :target-z 6.0
                               :hit? true
                               :target-uuid "target-2"}]
              [:flesh-ripping {:mode :end}]]
             @enqueued*)))))

(deftest enqueue-perform-emits-particles-and-sound-test
  (let [particles* (atom [])
        sounds* (atom [])
        enqueue! (var-get #'cn.li.ac.content.ability.teleporter.flesh-ripping-fx/enqueue!)]
    (with-redefs [client-particles/queue-particle-effect! (fn [payload]
                                                             (swap! particles* conj payload)
                                                             nil)
                  client-sounds/queue-sound-effect! (fn [payload]
                                                      (swap! sounds* conj payload)
                                                      nil)]
      (enqueue! {:mode :perform
                 :target-x 1.0 :target-y 2.0 :target-z 3.0
                 :hit? true
                 :target-uuid "target-1"})
      (is (= 2 (count @particles*)))
      (is (= 1 (count @sounds*)))
      (is (= "my_mod:tp.flesh_ripping" (:sound-id (first @sounds*)))))))

(deftest enqueue-end-clears-state-test
  (let [enqueue! (var-get #'cn.li.ac.content.ability.teleporter.flesh-ripping-fx/enqueue!)]
    (enqueue! {:mode :start})
    (enqueue! {:mode :update :target-x 1.0 :target-y 2.0 :target-z 3.0 :hit? false})
    (is (some? @(var-get #'cn.li.ac.content.ability.teleporter.flesh-ripping-fx/fx-state)))
    (enqueue! {:mode :end})
    (is (nil? @(var-get #'cn.li.ac.content.ability.teleporter.flesh-ripping-fx/fx-state)))))
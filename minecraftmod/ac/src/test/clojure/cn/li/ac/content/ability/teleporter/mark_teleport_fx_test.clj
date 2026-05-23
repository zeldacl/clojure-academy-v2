(ns cn.li.ac.content.ability.teleporter.mark-teleport-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.content.ability.teleporter.mark-teleport-fx :as mfx]))

(defn- reset-fixture [f]
  (reset! (var-get #'cn.li.ac.content.ability.teleporter.mark-teleport-fx/effect-state) nil)
  (f)
  (reset! (var-get #'cn.li.ac.content.ability.teleporter.mark-teleport-fx/effect-state) nil))

(use-fixtures :each reset-fixture)

(deftest init-registers-mark-teleport-fx-channels-test
  (let [registered-level* (atom nil)
        registered-handler* (atom nil)]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                         (reset! registered-level* [effect-id effect-map])
                                                         nil)
                  fx-registry/register-fx-channels! (fn [channels handler]
                                                      (reset! registered-handler* {:channels channels
                                                                                   :handler handler})
                                                      nil)]
      (mfx/init!)
      (is (= :mark-teleport (first @registered-level*)))
      (is (= #{:mark-teleport/fx-start
               :mark-teleport/fx-update
               :mark-teleport/fx-perform
               :mark-teleport/fx-end}
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
      (mfx/init!)
      (@handler* "ctx-1" :mark-teleport/fx-start nil)
      (@handler* "ctx-1" :mark-teleport/fx-update {:target {:x 1.0 :y 2.0 :z 3.0}
                                                    :distance 5.0})
      (@handler* "ctx-1" :mark-teleport/fx-perform {:target {:x 4.0 :y 5.0 :z 6.0}
                                                     :distance 7.0})
      (@handler* "ctx-1" :mark-teleport/fx-end nil)

      (is (= [[:mark-teleport {:mode :start}]
              [:mark-teleport {:mode :update :target {:x 1.0 :y 2.0 :z 3.0} :distance 5.0}]
              [:mark-teleport {:mode :perform :target {:x 4.0 :y 5.0 :z 6.0} :distance 7.0}]
              [:mark-teleport {:mode :end}]]
             @enqueued*)))))

(deftest enqueue-perform-with-target-emits-particles-and-sound-test
  (let [particles* (atom [])
        sounds* (atom [])
        enqueue! (var-get #'cn.li.ac.content.ability.teleporter.mark-teleport-fx/enqueue!)]
    (with-redefs [client-particles/queue-particle-effect! (fn [payload]
                                                             (swap! particles* conj payload)
                                                             nil)
                  client-sounds/queue-sound-effect! (fn [payload]
                                                      (swap! sounds* conj payload)
                                                      nil)]
      (enqueue! {:mode :perform :target {:x 2.0 :y 64.0 :z 3.0} :distance 8.0})
      (is (= 1 (count @particles*)))
      (is (= 1 (count @sounds*)))
      (is (= "my_mod:tp.tp" (:sound-id (first @sounds*)))))))

(deftest enqueue-perform-without-target-does-not-emit-audio-or-particles-test
  (let [particles* (atom 0)
        sounds* (atom 0)
        enqueue! (var-get #'cn.li.ac.content.ability.teleporter.mark-teleport-fx/enqueue!)]
    (with-redefs [client-particles/queue-particle-effect! (fn [& _] (swap! particles* inc) nil)
                  client-sounds/queue-sound-effect! (fn [& _] (swap! sounds* inc) nil)]
      (enqueue! {:mode :perform :distance 4.0})
      (is (= 0 @particles*))
      (is (= 0 @sounds*)))))

(deftest enqueue-end-clears-state-test
  (let [enqueue! (var-get #'cn.li.ac.content.ability.teleporter.mark-teleport-fx/enqueue!)]
    (enqueue! {:mode :start})
    (enqueue! {:mode :update :target {:x 1.0 :y 2.0 :z 3.0} :distance 2.0})
    (is (some? @(var-get #'cn.li.ac.content.ability.teleporter.mark-teleport-fx/effect-state)))
    (enqueue! {:mode :end})
    (is (nil? @(var-get #'cn.li.ac.content.ability.teleporter.mark-teleport-fx/effect-state)))))

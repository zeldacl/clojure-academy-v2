(ns cn.li.ac.content.ability.teleporter.mark-teleport-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.content.ability.teleporter.mark-teleport-fx :as mfx]))

(defn- with-fresh-mark-teleport-fx-runtime [f]
  (mfx/call-with-mark-teleport-fx-runtime
    (mfx/create-mark-teleport-fx-runtime)
    (fn []
      (try
        (f)
        (finally
          (mfx/reset-mark-teleport-fx-for-test!))))))

(use-fixtures :each with-fresh-mark-teleport-fx-runtime)

(defn- event [payload]
  {:payload payload
   :ctx-id "ctx-1"
   :channel :mark-teleport/fx-test
   :owner-key [:ctx "ctx-1"]})

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
                  level-effects/enqueue-level-effect! (fn [effect-id payload fx-context]
                                                        (swap! enqueued* conj [effect-id payload fx-context])
                                                        nil)]
      (mfx/init!)
      (@handler* "ctx-1" :mark-teleport/fx-start nil)
      (@handler* "ctx-1" :mark-teleport/fx-update {:target {:x 1.0 :y 2.0 :z 3.0}
                                                    :distance 5.0})
      (@handler* "ctx-1" :mark-teleport/fx-perform {:target {:x 4.0 :y 5.0 :z 6.0}
                                                     :distance 7.0})
      (@handler* "ctx-1" :mark-teleport/fx-end nil)

            (is (= [[:mark-teleport {:mode :start} {:ctx-id "ctx-1" :channel :mark-teleport/fx-start}]
              [:mark-teleport {:mode :update :target {:x 1.0 :y 2.0 :z 3.0} :distance 5.0}
               {:ctx-id "ctx-1" :channel :mark-teleport/fx-update}]
              [:mark-teleport {:mode :perform :target {:x 4.0 :y 5.0 :z 6.0} :distance 7.0}
               {:ctx-id "ctx-1" :channel :mark-teleport/fx-perform}]
              [:mark-teleport {:mode :end} {:ctx-id "ctx-1" :channel :mark-teleport/fx-end}]]
             @enqueued*)))))

(deftest enqueue-perform-with-target-emits-particles-and-sound-test
  (let [particles* (atom [])
        sounds* (atom [])
        enqueue! (var-get #'cn.li.ac.content.ability.teleporter.mark-teleport-fx/enqueue!)]
    (with-redefs [client-particles/current-effect-owner (fn [] {:client-session-id "mark-teleport-test"})
                  client-particles/queue-particle-effect! (fn [& args]
                                                            (swap! particles* conj args)
                                                            nil)
                  client-sounds/queue-sound-effect! (fn [& args]
                                                      (swap! sounds* conj args)
                                                      nil)]
      (enqueue! (event {:mode :perform :target {:x 2.0 :y 64.0 :z 3.0} :distance 8.0}))
      (is (= 1 (count @particles*)))
      (is (= 1 (count @sounds*)))
      (is (= "my_mod:tp.tp" (:sound-id (second (first @sounds*))))))))

(deftest enqueue-perform-without-target-does-not-emit-audio-or-particles-test
  (let [particles* (atom 0)
        sounds* (atom 0)
        enqueue! (var-get #'cn.li.ac.content.ability.teleporter.mark-teleport-fx/enqueue!)]
    (with-redefs [client-particles/current-effect-owner (fn [] {:client-session-id "mark-teleport-test"})
                  client-particles/queue-particle-effect! (fn [& _] (swap! particles* inc) nil)
                  client-sounds/queue-sound-effect! (fn [& _] (swap! sounds* inc) nil)]
      (enqueue! (event {:mode :perform :distance 4.0}))
      (is (= 0 @particles*))
      (is (= 0 @sounds*)))))

(deftest enqueue-end-clears-state-test
  (let [enqueue! (var-get #'cn.li.ac.content.ability.teleporter.mark-teleport-fx/enqueue!)]
    (with-redefs [client-particles/current-effect-owner (fn [] {:client-session-id "mark-teleport-test"})]
      (enqueue! (event {:mode :start}))
      (enqueue! (event {:mode :update :target {:x 1.0 :y 2.0 :z 3.0} :distance 2.0}))
      (is (some? (get (:effect-state (mfx/mark-teleport-fx-snapshot)) [:ctx "ctx-1"])))
      (enqueue! (event {:mode :end}))
      (is (nil? (get (:effect-state (mfx/mark-teleport-fx-snapshot)) [:ctx "ctx-1"]))))) )

(deftest mark-teleport-fx-runtime-isolation-test
  (let [runtime-a (mfx/create-mark-teleport-fx-runtime)
        runtime-b (mfx/create-mark-teleport-fx-runtime)
        enqueue! (var-get #'cn.li.ac.content.ability.teleporter.mark-teleport-fx/enqueue!)]
    (with-redefs [client-particles/current-effect-owner (fn [] {:client-session-id "mark-teleport-test"})
                  client-particles/queue-particle-effect! (fn [& _] nil)
                  client-sounds/queue-sound-effect! (fn [& _] nil)]
      (mfx/call-with-mark-teleport-fx-runtime
        runtime-a
        (fn []
          (enqueue! {:payload {:mode :start}
                     :ctx-id "ctx-a"
                     :channel :mark-teleport/fx-test
                     :owner-key [:ctx "ctx-a"]})
          (is (= #{[:ctx "ctx-a"]}
                 (set (keys (:effect-state (mfx/mark-teleport-fx-snapshot))))))))
      (mfx/call-with-mark-teleport-fx-runtime
        runtime-b
        (fn []
          (is (= {:effect-state {}}
                 (mfx/mark-teleport-fx-snapshot)))
          (enqueue! {:payload {:mode :start}
                     :ctx-id "ctx-b"
                     :channel :mark-teleport/fx-test
                     :owner-key [:ctx "ctx-b"]})
          (is (= #{[:ctx "ctx-b"]}
                 (set (keys (:effect-state (mfx/mark-teleport-fx-snapshot))))))))
      (mfx/call-with-mark-teleport-fx-runtime
        runtime-a
        (fn []
          (is (= #{[:ctx "ctx-a"]}
                 (set (keys (:effect-state (mfx/mark-teleport-fx-snapshot)))))))))))

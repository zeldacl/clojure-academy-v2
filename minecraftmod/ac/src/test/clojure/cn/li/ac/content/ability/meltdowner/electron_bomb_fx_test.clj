(ns cn.li.ac.content.ability.meltdowner.electron-bomb-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.content.ability.meltdowner.electron-bomb-fx :as electron-bomb-fx]))

(defn- reset-fixture [f]
  (electron-bomb-fx/call-with-electron-bomb-fx-runtime
    (electron-bomb-fx/create-electron-bomb-fx-runtime)
    (fn []
      (electron-bomb-fx/reset-electron-bomb-fx-for-test!)
      (try
        (f)
        (finally
          (electron-bomb-fx/reset-electron-bomb-fx-for-test!))))))

(use-fixtures :each reset-fixture)

(defn- event [ctx-id channel payload]
  {:payload payload
   :ctx-id ctx-id
   :channel channel
   :owner-key [:ctx ctx-id]})

(deftest init-registers-owner-aware-electron-bomb-fx-test
  (let [registered-level* (atom nil)
        registered-handler* (atom nil)]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                         (reset! registered-level* [effect-id effect-map])
                                                         nil)
                  fx-registry/register-fx-channels! (fn [channels handler]
                                                      (reset! registered-handler* {:channels channels
                                                                                   :handler handler})
                                                      nil)]
      (electron-bomb-fx/init!)
      (is (= :electron-bomb (first @registered-level*)))
      (is (fn? (:enqueue-event-fn (second @registered-level*))))
      (is (= #{:electron-bomb/fx-spawn
               :electron-bomb/fx-beam
               :electron-bomb/fx-end}
             (set (:channels @registered-handler*)))))))

(deftest fx-handler-routes-events-with-ctx-metadata-test
  (let [handler* (atom nil)
        enqueued* (atom [])]
    (with-redefs [level-effects/register-level-effect! (fn [& _] nil)
                  fx-registry/register-fx-channels! (fn [_ handler]
                                                      (reset! handler* handler)
                                                      nil)
                  level-effects/enqueue-level-effect! (fn [effect-id payload fx-context]
                                                        (swap! enqueued* conj [effect-id payload fx-context])
                                                        nil)]
      (electron-bomb-fx/init!)
      (@handler* "ctx-eb" :electron-bomb/fx-spawn {:x 1.0 :y 64.0 :z 2.0 :dx 0.0 :dy 0.0 :dz 1.0})
      (@handler* "ctx-eb" :electron-bomb/fx-beam {:start {:x 1.0 :y 64.0 :z 2.0}
                                                   :end {:x 1.0 :y 64.0 :z 17.0}
                                                   :performed? true
                                                   :target-uuid "target-1"})
      (@handler* "ctx-eb" :electron-bomb/fx-end {})
      (is (= [[:electron-bomb {:mode :spawn
                               :x 1.0 :y 64.0 :z 2.0
                               :dx 0.0 :dy 0.0 :dz 1.0}
               {:ctx-id "ctx-eb" :channel :electron-bomb/fx-spawn}]
              [:electron-bomb {:mode :beam
                               :start {:x 1.0 :y 64.0 :z 2.0}
                               :end {:x 1.0 :y 64.0 :z 17.0}}
               {:ctx-id "ctx-eb" :channel :electron-bomb/fx-beam}]
              [:electron-bomb {:mode :end}
               {:ctx-id "ctx-eb" :channel :electron-bomb/fx-end}]]
             @enqueued*)))))

(deftest build-plan-renders-active-orb-and-beam-flash-test
  (let [enqueue! (var-get #'cn.li.ac.content.ability.meltdowner.electron-bomb-fx/enqueue!)
        build-plan (var-get #'cn.li.ac.content.ability.meltdowner.electron-bomb-fx/build-plan)]
    (with-redefs [client-particles/current-effect-owner (fn [] {:client-session-id "electron-bomb-fx-test"})
                  client-particles/queue-particle-effect! (fn [& _] nil)
                  client-sounds/queue-sound-effect! (fn [& _] nil)]
      (enqueue! (event "ctx-a" :electron-bomb/fx-spawn
                       {:mode :spawn :x 1.0 :y 64.0 :z 2.0 :dx 0.0 :dy 0.0 :dz 1.0}))
      (let [spawn-plan (build-plan {:x 0.0 :y 65.0 :z 0.0} nil 0)]
        (is (seq (:ops spawn-plan))))
      (enqueue! (event "ctx-a" :electron-bomb/fx-beam
                       {:mode :beam
                        :start {:x 1.0 :y 64.0 :z 2.0}
                        :end {:x 1.0 :y 64.0 :z 17.0}
                        :performed? true
                        :target-uuid "target-1"}))
      (let [beam-plan (build-plan {:x 0.0 :y 65.0 :z 0.0} nil 0)]
        (is (seq (:ops beam-plan)))
        (is (some #(= :quad (:kind %)) (:ops beam-plan)))))))

(deftest beam-event-clears-active-owner-and-leaves-transient-flash-test
  (let [enqueue! (var-get #'cn.li.ac.content.ability.meltdowner.electron-bomb-fx/enqueue!)
        build-plan (var-get #'cn.li.ac.content.ability.meltdowner.electron-bomb-fx/build-plan)
        tick! (var-get #'cn.li.ac.content.ability.meltdowner.electron-bomb-fx/tick!)]
    (with-redefs [client-particles/current-effect-owner (fn [] {:client-session-id "electron-bomb-fx-test"})
                  client-particles/queue-particle-effect! (fn [& _] nil)
                  client-sounds/queue-sound-effect! (fn [& _] nil)]
      (enqueue! (event "ctx-a" :electron-bomb/fx-spawn
                       {:mode :spawn :x 1.0 :y 64.0 :z 2.0 :dx 0.0 :dy 0.0 :dz 1.0}))
      (enqueue! (event "ctx-a" :electron-bomb/fx-beam
                       {:mode :beam
                        :start {:x 1.0 :y 64.0 :z 2.0}
                        :end {:x 1.0 :y 64.0 :z 17.0}}))
      (is (nil? (get-in (electron-bomb-fx/electron-bomb-fx-snapshot) [:effect-state [:ctx "ctx-a"]])))
      (is (seq (get-in (electron-bomb-fx/electron-bomb-fx-snapshot) [:beams [:ctx "ctx-a"]])))
      (is (seq (:ops (build-plan {:x 0.0 :y 65.0 :z 0.0} nil 0))))
      (dotimes [_ 9]
        (tick!))
      (is (nil? (build-plan {:x 0.0 :y 65.0 :z 0.0} nil 0))))))

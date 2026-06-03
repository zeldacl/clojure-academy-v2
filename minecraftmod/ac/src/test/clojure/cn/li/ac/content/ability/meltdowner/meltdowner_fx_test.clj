(ns cn.li.ac.content.ability.meltdowner.meltdowner-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.content.ability.meltdowner.meltdowner-fx :as md-fx]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(defn- reset-fixture [f]
  (binding [runtime-hooks/*client-session-id* :test-session]
    (level-effects/call-with-level-effect-runtime
      (level-effects/create-level-effect-runtime)
      (fn []
        (try
          (level-effects/reset-level-effect-registry-for-test!)
          (md-fx/reset-meltdowner-fx-for-test!)
          (f)
          (finally
            (md-fx/reset-meltdowner-fx-for-test!)
            (level-effects/reset-level-effect-registry-for-test!)))))))

(use-fixtures :each reset-fixture)

(defn- event
  [ctx-id channel payload]
  {:payload payload
   :ctx-id ctx-id
   :channel channel
   :owner-key [:ctx ctx-id]})

(deftest fx-handler-routes-meltdowner-channels-test
  (let [handler* (atom nil)
        enqueued* (atom [])]
    (with-redefs [level-effects/register-level-effect! (fn [& _] nil)
                  fx-registry/register-fx-channels! (fn [_ handler]
                                                      (reset! handler* handler)
                                                      nil)
                  level-effects/enqueue-level-effect! (fn [effect-id payload fx-context]
                                                        (swap! enqueued* conj [effect-id payload fx-context])
                                                        nil)]
      (md-fx/init!)
      (@handler* "ctx-md" :meltdowner/fx-start {:source-player-id "player-a"})
      (@handler* "ctx-md" :meltdowner/fx-update {:ticks 9
                                                  :charge-ratio 0.7
                                                  :source-player-id "player-a"})
      (@handler* "ctx-md" :meltdowner/fx-perform {:start {:x 0.0 :y 64.0 :z 0.0}
                                                   :end {:x 2.0 :y 64.0 :z 2.0}
                                                   :charge-ticks 18
                                                   :beam-length 24.0
                                                   :source-player-id "player-a"})
      (@handler* "ctx-md" :meltdowner/fx-reflect {:start {:x 0.0 :y 64.0 :z 0.0}
                                                   :end {:x 1.0 :y 65.0 :z 1.0}
                                                   :source-player-id "player-a"})
      (is (= [[:meltdowner {:source-player-id "player-a" :mode :start}
               {:ctx-id "ctx-md" :channel :meltdowner/fx-start}]
              [:meltdowner {:source-player-id "player-a"
                            :mode :update
                            :ticks 9
                            :charge-ratio 0.7}
               {:ctx-id "ctx-md" :channel :meltdowner/fx-update}]
              [:meltdowner {:source-player-id "player-a"
                            :mode :perform
                            :charge-ticks 18
                            :beam-length 24.0
                            :start {:x 0.0 :y 64.0 :z 0.0}
                            :end {:x 2.0 :y 64.0 :z 2.0}}
               {:ctx-id "ctx-md" :channel :meltdowner/fx-perform}]
              [:meltdowner {:source-player-id "player-a"
                            :mode :reflect
                            :start {:x 0.0 :y 64.0 :z 0.0}
                            :end {:x 1.0 :y 65.0 :z 1.0}}
               {:ctx-id "ctx-md" :channel :meltdowner/fx-reflect}]]
             @enqueued*)))))

(deftest start-update-perform-end-manage-state-test
  (let [enqueue! (var-get #'cn.li.ac.content.ability.meltdowner.meltdowner-fx/enqueue!)]
    (level-effects/update-effect-state! :meltdowner
      enqueue!
      (event "ctx-a" :meltdowner/fx-start {:mode :start :source-player-id "player-a"}))
    (level-effects/update-effect-state! :meltdowner
      enqueue!
      (event "ctx-a" :meltdowner/fx-update {:mode :update
                                             :ticks 10
                                             :charge-ratio 0.5
                                             :source-player-id "player-a"}))
    (is (some? (get-in (md-fx/meltdowner-fx-snapshot) [:effect-state [:ctx "ctx-a"]])))
    (level-effects/update-effect-state! :meltdowner
      enqueue!
      (event "ctx-a" :meltdowner/fx-perform {:mode :perform
                                              :start {:x 1.0 :y 64.0 :z 0.0}
                                              :end {:x 2.0 :y 64.0 :z 1.0}
                                              :charge-ticks 20
                                              :beam-length 30.0
                                              :source-player-id "player-a"}))
    (is (some? (get-in (md-fx/meltdowner-fx-snapshot) [:rays [:ctx "ctx-a"]])))
    (level-effects/update-effect-state! :meltdowner
      enqueue!
      (event "ctx-a" :meltdowner/fx-end {:mode :end
                                          :performed? true
                                          :source-player-id "player-a"}))
    (let [snapshot (md-fx/meltdowner-fx-snapshot)]
      (is (false? (get-in snapshot [:effect-state [:ctx "ctx-a"] :active?])))
      (is (some? (get-in snapshot [:rays [:ctx "ctx-a"]]))))))

(deftest build-plan-and-tick-state-test
  (let [enqueue! (var-get #'cn.li.ac.content.ability.meltdowner.meltdowner-fx/enqueue!)
        tick! (var-get #'cn.li.ac.content.ability.meltdowner.meltdowner-fx/tick!)
        build-plan (var-get #'cn.li.ac.content.ability.meltdowner.meltdowner-fx/build-plan)
        sounds* (atom [])]
    (with-redefs [client-sounds/queue-sound-effect! (fn [& args]
                                                       (swap! sounds* conj args)
                                                       nil)
                  client-sounds/current-effect-owner (fn [] :test-owner)
                  rand-int (fn [_] 0)]
      (level-effects/update-effect-state! :meltdowner
        enqueue!
        (event "ctx-main" :meltdowner/fx-start {:mode :start :source-player-id "player-a"}))
      (level-effects/update-effect-state! :meltdowner
        enqueue!
        (event "ctx-main" :meltdowner/fx-update {:mode :update
                                                  :ticks 8
                                                  :charge-ratio 0.8
                                                  :source-player-id "player-a"}))
      (level-effects/update-effect-state! :meltdowner
        enqueue!
        (event "ctx-main" :meltdowner/fx-perform {:mode :perform
                                                   :start {:x 0.0 :y 64.0 :z 0.0}
                                                   :end {:x 2.0 :y 64.0 :z 2.0}
                                                   :source-player-id "player-a"}))
      (is (some? (build-plan {:x 0.0 :y 65.0 :z 0.0}
                             {:player-uuid "player-a" :x 0.0 :y 64.0 :z 0.0}
                             0)))
      (tick!)
      (is (seq @sounds*))
      (is (some? (build-plan {:x 0.0 :y 65.0 :z 0.0}
                             {:player-uuid "player-a" :x 0.0 :y 64.0 :z 0.0}
                             1))))))

(deftest charge-loop-cadence-and-ray-expiry-test
  (let [enqueue! (var-get #'cn.li.ac.content.ability.meltdowner.meltdowner-fx/enqueue!)
        tick! (var-get #'cn.li.ac.content.ability.meltdowner.meltdowner-fx/tick!)
        build-plan (var-get #'cn.li.ac.content.ability.meltdowner.meltdowner-fx/build-plan)
        sounds* (atom [])]
    (with-redefs [client-sounds/queue-sound-effect! (fn [& args]
                                                       (swap! sounds* conj args)
                                                       nil)
                  client-sounds/current-effect-owner (fn [] :test-owner)
                  rand-int (fn [_] 0)]
      (level-effects/update-effect-state! :meltdowner
        enqueue!
        (event "ctx-cadence" :meltdowner/fx-start {:mode :start :source-player-id "player-a"}))
      (level-effects/update-effect-state! :meltdowner
        enqueue!
        (event "ctx-cadence" :meltdowner/fx-perform {:mode :perform
                                                      :start {:x 0.0 :y 64.0 :z 0.0}
                                                      :end {:x 2.0 :y 64.0 :z 2.0}
                                                      :source-player-id "player-a"}))

      ;; one immediate charge sound from :start, then one loop sound every 10 ticks while active
      (dotimes [_ 20]
        (tick!))

        (is (= 4 (count @sounds*))
          "start + perform fire + loop sounds at tick 10 and tick 20")

      ;; perform ray gets deterministic ttl 16 when rand-int is stubbed to 0
      (is (some? (build-plan {:x 0.0 :y 65.0 :z 0.0}
                             {:player-uuid "player-a" :x 0.0 :y 64.0 :z 0.0}
                             20)))
      (dotimes [_ 16]
        (tick!))
      (is (nil? (get-in (md-fx/meltdowner-fx-snapshot) [:rays [:ctx "ctx-cadence"]]))))))

(deftest meltdowner-fx-runtime-isolation-test
  (let [runtime-a (level-effects/create-level-effect-runtime)
        runtime-b (level-effects/create-level-effect-runtime)
        enqueue! (var-get #'cn.li.ac.content.ability.meltdowner.meltdowner-fx/enqueue!)]
    (level-effects/call-with-level-effect-runtime
      runtime-a
      (fn []
        (level-effects/update-effect-state! :meltdowner
          enqueue!
          (event "ctx-a" :meltdowner/fx-start {:mode :start :source-player-id "player-a"}))
        (is (= #{[:ctx "ctx-a"]}
               (set (keys (:effect-state (md-fx/meltdowner-fx-snapshot))))))))
    (level-effects/call-with-level-effect-runtime
      runtime-b
      (fn []
        (is (= {:effect-state {}
                :rays {}}
               (md-fx/meltdowner-fx-snapshot)))
        (level-effects/update-effect-state! :meltdowner
          enqueue!
          (event "ctx-b" :meltdowner/fx-start {:mode :start :source-player-id "player-b"}))
        (is (= #{[:ctx "ctx-b"]}
               (set (keys (:effect-state (md-fx/meltdowner-fx-snapshot))))))))
    (level-effects/call-with-level-effect-runtime
      runtime-a
      (fn []
        (is (= #{[:ctx "ctx-a"]}
               (set (keys (:effect-state (md-fx/meltdowner-fx-snapshot))))))))))

(ns cn.li.ac.content.ability.meltdowner.meltdowner-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.content.ability.meltdowner.meltdowner-fx :as md-fx]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(defn- reset-fixture [f]
  (runtime-hooks/with-client-ctx {:session-id :test-session}
    (try
          (level-effects/reset-level-effect-registry-for-test!)
          (md-fx/reset-fx-for-test!)
          (f)
          (finally
            (md-fx/reset-fx-for-test!)
            (level-effects/reset-level-effect-registry-for-test!)))))

(use-fixtures :each reset-fixture)

(defn- event
  [ctx-id channel payload]
  {:payload payload
   :ctx-id ctx-id
   :channel channel
   :owner-key [:ctx ctx-id]})

(deftest init-registers-owner-aware-meltdowner-fx-test
  (let [registered-level* (atom nil)
        registered-topics* (atom #{})]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                         (reset! registered-level* [effect-id effect-map])
                                                         nil)
                  fx-registry/register-fx-channel! (fn [topic _handler]
                                                      (swap! registered-topics* conj topic)
                                                      nil)]
      (md-fx/init!)
      (is (= :meltdowner (first @registered-level*)))
      (is (fn? (:enqueue-state-fn (second @registered-level*))))
      (is (= #{:meltdowner/fx-start
               :meltdowner/fx-update
               :meltdowner/fx-end
               :meltdowner/fx-perform
               :meltdowner/fx-reflect}
             @registered-topics*)))))

(deftest fx-handler-routes-meltdowner-channels-test
  (let [handlers* (atom {})
        enqueued* (atom [])]
    (with-redefs [level-effects/register-level-effect! (fn [& _] nil)
                  fx-registry/register-fx-channel! (fn [topic handler]
                                                      (swap! handlers* assoc topic handler)
                                                      nil)
                  level-effects/enqueue-level-effect! (fn [effect-id ctx-id channel payload & opts]
                                                        (swap! enqueued* conj [effect-id ctx-id channel payload opts])
                                                        nil)]
      (md-fx/init!)
      ((get @handlers* :meltdowner/fx-start) "ctx-md" :meltdowner/fx-start {:source-player-id "player-a"})
      ((get @handlers* :meltdowner/fx-update) "ctx-md" :meltdowner/fx-update {:ticks 9
                                                  :charge-ratio 0.7
                                                  :source-player-id "player-a"})
      ((get @handlers* :meltdowner/fx-perform) "ctx-md" :meltdowner/fx-perform {:start {:x 0.0 :y 64.0 :z 0.0}
                                                   :end {:x 2.0 :y 64.0 :z 2.0}
                                                   :charge-ticks 18
                                                   :beam-length 24.0
                                                   :source-player-id "player-a"})
      ((get @handlers* :meltdowner/fx-reflect) "ctx-md" :meltdowner/fx-reflect {:start {:x 0.0 :y 64.0 :z 0.0}
                                                   :end {:x 1.0 :y 65.0 :z 1.0}
                                                   :source-player-id "player-a"})
      (is (= [[:meltdowner {:source-player-id "player-a"
                            :mode :start
                            :owner-key [:ctx "ctx-md"]
                            :ctx-id "ctx-md"
                            :channel :meltdowner/fx-start}
               {:ctx-id "ctx-md"
                :channel :meltdowner/fx-start
                :owner-key [:ctx "ctx-md"]}]
              [:meltdowner {:source-player-id "player-a"
                            :mode :update
                            :owner-key [:ctx "ctx-md"]
                            :ctx-id "ctx-md"
                            :channel :meltdowner/fx-update
                            :ticks 9
                            :charge-ratio 0.7}
               {:ctx-id "ctx-md"
                :channel :meltdowner/fx-update
                :owner-key [:ctx "ctx-md"]}]
              [:meltdowner {:source-player-id "player-a"
                            :mode :perform
                            :owner-key [:ctx "ctx-md"]
                            :ctx-id "ctx-md"
                            :channel :meltdowner/fx-perform
                            :charge-ticks 18
                            :beam-length 24.0
                            :start {:x 0.0 :y 64.0 :z 0.0}
                            :end {:x 2.0 :y 64.0 :z 2.0}}
               {:ctx-id "ctx-md"
                :channel :meltdowner/fx-perform
                :owner-key [:ctx "ctx-md"]}]
              [:meltdowner {:source-player-id "player-a"
                            :mode :reflect
                            :owner-key [:ctx "ctx-md"]
                            :ctx-id "ctx-md"
                            :channel :meltdowner/fx-reflect
                            :start {:x 0.0 :y 64.0 :z 0.0}
                            :end {:x 1.0 :y 65.0 :z 1.0}}
               {:ctx-id "ctx-md"
                :channel :meltdowner/fx-reflect
                :owner-key [:ctx "ctx-md"]}]]
             @enqueued*)))))

(deftest start-update-perform-end-manage-state-test
  (do
    (arc-beam/enqueue-for-test! :meltdowner "ctx-a" :meltdowner/fx-start {:mode :start :source-player-id "player-a"})
    (arc-beam/enqueue-for-test! :meltdowner "ctx-a" :meltdowner/fx-update {:mode :update
                                             :ticks 10
                                             :charge-ratio 0.5
                                             :source-player-id "player-a"})
    (is (some? (get-in (md-fx/fx-snapshot) [:effect-state [:ctx "ctx-a"]])))
    (arc-beam/enqueue-for-test! :meltdowner "ctx-a" :meltdowner/fx-perform {:mode :perform
                                              :start {:x 1.0 :y 64.0 :z 0.0}
                                              :end {:x 2.0 :y 64.0 :z 1.0}
                                              :charge-ticks 20
                                              :beam-length 30.0
                                              :source-player-id "player-a"})
    (is (some? (get-in (md-fx/fx-snapshot) [:rays [:ctx "ctx-a"]])))
    (arc-beam/enqueue-for-test! :meltdowner "ctx-a" :meltdowner/fx-end {:mode :end
                                          :performed? true
                                          :source-player-id "player-a"})
    (let [snapshot (md-fx/fx-snapshot)]
      (is (false? (get-in snapshot [:effect-state [:ctx "ctx-a"] :active?])))
      (is (some? (get-in snapshot [:rays [:ctx "ctx-a"]]))))))

(deftest build-plan-and-tick-state-test
  (let [
        sounds* (atom [])]
    (with-redefs [client-sounds/queue-sound-effect! (fn [& args]
                                                       (swap! sounds* conj args)
                                                       nil)
                  client-sounds/current-effect-owner (fn [] :test-owner)
                  rand-int (fn [_] 0)]
      (arc-beam/enqueue-for-test! :meltdowner "ctx-main" :meltdowner/fx-start {:mode :start :source-player-id "player-a"})
      (arc-beam/enqueue-for-test! :meltdowner "ctx-main" :meltdowner/fx-update {:mode :update
                                                  :ticks 8
                                                  :charge-ratio 0.8
                                                  :source-player-id "player-a"})
      (arc-beam/enqueue-for-test! :meltdowner "ctx-main" :meltdowner/fx-perform {:mode :perform
                                                   :start {:x 0.0 :y 64.0 :z 0.0}
                                                   :end {:x 2.0 :y 64.0 :z 2.0}
                                                   :source-player-id "player-a"})
      (is (some? (arc-beam/effect-build-plan :meltdowner {:x 0.0 :y 65.0 :z 0.0}
                             {:player-uuid "player-a" :x 0.0 :y 64.0 :z 0.0}
                             0)))
      (level-effects/update-effect-state! :meltdowner
        (fn [store] (arc-beam/effect-tick-state! :level :meltdowner store)))
      (is (seq @sounds*))
      (is (some? (arc-beam/effect-build-plan :meltdowner {:x 0.0 :y 65.0 :z 0.0}
                             {:player-uuid "player-a" :x 0.0 :y 64.0 :z 0.0}
                             1))))))

(deftest charge-loop-cadence-and-ray-expiry-test
  (let [
        sounds* (atom [])]
    (with-redefs [client-sounds/queue-sound-effect! (fn [& args]
                                                       (swap! sounds* conj args)
                                                       nil)
                  client-sounds/current-effect-owner (fn [] :test-owner)
                  rand-int (fn [_] 0)]
      (arc-beam/enqueue-for-test! :meltdowner "ctx-cadence" :meltdowner/fx-start {:mode :start :source-player-id "player-a"})
      (arc-beam/enqueue-for-test! :meltdowner "ctx-cadence" :meltdowner/fx-perform {:mode :perform
                                                      :start {:x 0.0 :y 64.0 :z 0.0}
                                                      :end {:x 2.0 :y 64.0 :z 2.0}
                                                      :source-player-id "player-a"})

      ;; one immediate charge sound from :start, then one loop sound every 10 ticks while active
      (dotimes [_ 20]
        (level-effects/update-effect-state! :meltdowner
          (fn [store] (arc-beam/effect-tick-state! :level :meltdowner store))))
      (is (= 4 (count @sounds*))
          "start + perform fire + loop sounds at tick 10 and tick 20")

      ;; perform ray gets deterministic ttl 16 when rand-int is stubbed to 0
      (is (some? (arc-beam/effect-build-plan :meltdowner {:x 0.0 :y 65.0 :z 0.0}
                             {:player-uuid "player-a" :x 0.0 :y 64.0 :z 0.0}
                             20)))
      (dotimes [_ 16]
        (level-effects/update-effect-state! :meltdowner
          (fn [store] (arc-beam/effect-tick-state! :level :meltdowner store))))
      (is (nil? (get-in (md-fx/fx-snapshot) [:rays [:ctx "ctx-cadence"]]))))))



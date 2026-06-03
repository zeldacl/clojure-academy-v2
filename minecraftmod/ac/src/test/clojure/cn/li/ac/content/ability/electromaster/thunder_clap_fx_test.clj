(ns cn.li.ac.content.ability.electromaster.thunder-clap-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.content.ability.electromaster.thunder-clap-fx :as thunder-clap-fx]))

(defn- reset-fixture [f]
  (level-effects/call-with-level-effect-runtime
    (level-effects/create-level-effect-runtime)
    (fn []
      (try
        (level-effects/reset-level-effect-registry-for-test!)
        (thunder-clap-fx/reset-thunder-clap-fx-for-test!)
        (f)
        (finally
          (thunder-clap-fx/reset-thunder-clap-fx-for-test!)
          (level-effects/reset-level-effect-registry-for-test!))))))

(use-fixtures :each reset-fixture)

(defn- event
  [ctx-id channel payload]
  {:payload payload
   :ctx-id ctx-id
   :channel channel
   :owner-key [:ctx ctx-id]})

(deftest init-registers-owner-aware-thunder-clap-fx-test
  (let [registered-level* (atom nil)
        registered-handler* (atom nil)]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                         (reset! registered-level* [effect-id effect-map])
                                                         nil)
                  fx-registry/register-fx-channels! (fn [channels handler]
                                                      (reset! registered-handler* {:channels channels
                                                                                   :handler handler})
                                                      nil)]
      (thunder-clap-fx/init!)
      (is (= :thunder-clap (first @registered-level*)))
      (is (fn? (:enqueue-state-fn (second @registered-level*))))
      (is (= #{:thunder-clap/fx-start
               :thunder-clap/fx-update
               :thunder-clap/fx-perform
               :thunder-clap/fx-end}
             (set (:channels @registered-handler*)))))))

(deftest fx-handler-routes-four-stages-with-ctx-metadata-test
  (let [handler* (atom nil)
        enqueued* (atom [])]
    (with-redefs [level-effects/register-level-effect! (fn [& _] nil)
                  fx-registry/register-fx-channels! (fn [_ handler]
                                                      (reset! handler* handler)
                                                      nil)
                  level-effects/enqueue-level-effect! (fn [effect-id payload fx-context]
                                                        (swap! enqueued* conj [effect-id payload fx-context])
                                                        nil)]
      (thunder-clap-fx/init!)
      (@handler* "ctx-tc" :thunder-clap/fx-start {:source-player-id "player-a"})
      (@handler* "ctx-tc" :thunder-clap/fx-update {:ticks 5
                                                    :charge-ratio 0.5
                                                    :target {:x 1.0 :y 64.0 :z 1.0}
                                                    :source-player-id "player-a"})
      (@handler* "ctx-tc" :thunder-clap/fx-perform {:performed? true
                                                     :charge-ticks 6
                                                     :charge-ratio 0.6
                                                     :target {:x 1.0 :y 64.0 :z 1.0}
                                                     :source-player-id "player-a"})
      (@handler* "ctx-tc" :thunder-clap/fx-end {:performed? true
                                                 :charge-ticks 6
                                                 :charge-ratio 0.6
                                                 :target {:x 1.0 :y 64.0 :z 1.0}
                                                 :source-player-id "player-a"})
      (is (= [[:thunder-clap {:source-player-id "player-a" :mode :start}
               {:ctx-id "ctx-tc" :channel :thunder-clap/fx-start}]
              [:thunder-clap {:source-player-id "player-a"
                              :mode :update
                              :ticks 5
                              :charge-ratio 0.5
                              :target {:x 1.0 :y 64.0 :z 1.0}}
               {:ctx-id "ctx-tc" :channel :thunder-clap/fx-update}]
              [:thunder-clap {:source-player-id "player-a"
                              :mode :perform
                              :performed? true
                              :ticks 6
                              :charge-ratio 0.6
                              :target {:x 1.0 :y 64.0 :z 1.0}}
               {:ctx-id "ctx-tc" :channel :thunder-clap/fx-perform}]
              [:thunder-clap {:source-player-id "player-a"
                              :mode :end
                              :performed? true
                              :ticks 6
                              :charge-ratio 0.6
                              :target {:x 1.0 :y 64.0 :z 1.0}}
               {:ctx-id "ctx-tc" :channel :thunder-clap/fx-end}]]
             @enqueued*)))))

(deftest start-update-perform-end-manage-state-test
  (let [enqueue-state! (var-get #'cn.li.ac.content.ability.electromaster.thunder-clap-fx/enqueue-state!)]
    (level-effects/update-effect-state! :thunder-clap
      enqueue-state!
      (event "ctx-a" :thunder-clap/fx-start {:mode :start :source-player-id "player-a"}))
    (level-effects/update-effect-state! :thunder-clap
      enqueue-state!
      (event "ctx-a" :thunder-clap/fx-update {:mode :update
                                                :ticks 10
                                                :charge-ratio 0.5
                                                :target {:x 1.0 :y 64.0 :z 0.0}
                                                :source-player-id "player-a"}))
    (is (some? (get-in (thunder-clap-fx/thunder-clap-fx-snapshot) [:effect-state [:ctx "ctx-a"]])))
    (level-effects/update-effect-state! :thunder-clap
      enqueue-state!
      (event "ctx-a" :thunder-clap/fx-perform {:mode :perform
                                                :performed? true
                                                :ticks 12
                                                :charge-ratio 0.6
                                                :target {:x 1.0 :y 64.0 :z 0.0}
                                                :source-player-id "player-a"}))
    (is (some? (get-in (thunder-clap-fx/thunder-clap-fx-snapshot) [:impacts [:ctx "ctx-a"]])))
    (level-effects/update-effect-state! :thunder-clap
      enqueue-state!
      (event "ctx-a" :thunder-clap/fx-end {:mode :end
                                            :performed? true
                                            :source-player-id "player-a"}))
    (let [snapshot (thunder-clap-fx/thunder-clap-fx-snapshot)]
      (is (nil? (get-in snapshot [:effect-state [:ctx "ctx-a"]])))
      (is (some? (get-in snapshot [:impacts [:ctx "ctx-a"]]))))))

(deftest perform-spawns-short-impact-ops-test
  (let [enqueue-state! (var-get #'cn.li.ac.content.ability.electromaster.thunder-clap-fx/enqueue-state!)
        build-plan (var-get #'cn.li.ac.content.ability.electromaster.thunder-clap-fx/build-plan)
        tick-state! (var-get #'cn.li.ac.content.ability.electromaster.thunder-clap-fx/tick-state!)]
    (level-effects/update-effect-state! :thunder-clap
      enqueue-state!
      (event "ctx-p" :thunder-clap/fx-perform {:mode :perform
                                                :performed? true
                                                :ticks 40
                                                :charge-ratio 0.8
                                                :target {:x 2.0 :y 70.0 :z 3.0}
                                                :source-player-id "player-a"}))
    (let [plan (build-plan {:x 0.0 :y 65.0 :z 0.0} nil 0)]
      (is (seq (:ops plan))))
    (level-effects/update-effect-state! :thunder-clap
      (fn [store _]
        (tick-state! store))
      nil)
    (is (seq (:ops (build-plan {:x 0.0 :y 65.0 :z 0.0} nil 0))))))

(deftest thunder-clap-fx-runtime-isolation-test
  (let [runtime-a (level-effects/create-level-effect-runtime)
        runtime-b (level-effects/create-level-effect-runtime)
        enqueue-state! (var-get #'cn.li.ac.content.ability.electromaster.thunder-clap-fx/enqueue-state!)]
    (level-effects/call-with-level-effect-runtime
      runtime-a
      (fn []
        (level-effects/update-effect-state! :thunder-clap
          enqueue-state!
          (event "ctx-a" :thunder-clap/fx-start {:mode :start :source-player-id "player-a"}))
        (is (= #{[:ctx "ctx-a"]}
               (set (keys (:effect-state (thunder-clap-fx/thunder-clap-fx-snapshot))))))))
    (level-effects/call-with-level-effect-runtime
      runtime-b
      (fn []
        (is (= {:effect-state {}
                :impacts {}}
               (thunder-clap-fx/thunder-clap-fx-snapshot)))
        (level-effects/update-effect-state! :thunder-clap
          enqueue-state!
          (event "ctx-b" :thunder-clap/fx-start {:mode :start :source-player-id "player-b"}))
        (is (= #{[:ctx "ctx-b"]}
               (set (keys (:effect-state (thunder-clap-fx/thunder-clap-fx-snapshot))))))))
    (level-effects/call-with-level-effect-runtime
      runtime-a
      (fn []
        (is (= #{[:ctx "ctx-a"]}
               (set (keys (:effect-state (thunder-clap-fx/thunder-clap-fx-snapshot))))))))))

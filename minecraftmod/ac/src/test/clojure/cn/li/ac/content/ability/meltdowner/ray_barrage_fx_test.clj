(ns cn.li.ac.content.ability.meltdowner.ray-barrage-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.content.ability.meltdowner.ray-barrage-fx :as rb-fx]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(defn- reset-fixture [f]
  (binding [runtime-hooks/*client-session-id* :test-session]
    (level-effects/call-with-level-effect-runtime
      (level-effects/create-level-effect-runtime)
      (fn []
        (try
          (level-effects/reset-level-effect-registry-for-test!)
          (rb-fx/reset-ray-barrage-fx-for-test!)
          (f)
          (finally
            (rb-fx/reset-ray-barrage-fx-for-test!)
            (level-effects/reset-level-effect-registry-for-test!)))))))

(use-fixtures :each reset-fixture)

(defn- event
  [ctx-id channel payload]
  {:payload payload
   :ctx-id ctx-id
   :channel channel
   :owner-key [:ctx ctx-id]})

(deftest init-registers-owner-aware-ray-barrage-fx-test
  (let [registered-level* (atom nil)
        registered-handler* (atom nil)]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                         (reset! registered-level* [effect-id effect-map])
                                                         nil)
                  fx-registry/register-fx-channels! (fn [channels handler]
                                                      (reset! registered-handler* {:channels channels
                                                                                   :handler handler})
                                                      nil)]
      (rb-fx/init!)
      (is (= :ray-barrage (first @registered-level*)))
      (is (fn? (:enqueue-state-fn (second @registered-level*))))
      (is (= #{:ray-barrage/fx-preray :ray-barrage/fx-barrage :ray-barrage/fx-beam}
             (set (:channels @registered-handler*)))))))

(deftest fx-handler-routes-ray-barrage-beam-test
  (let [handler* (atom nil)
        enqueued* (atom [])
        sounds* (atom [])]
    (with-redefs [level-effects/register-level-effect! (fn [& _] nil)
                  fx-registry/register-fx-channels! (fn [_ handler]
                                                      (reset! handler* handler)
                                                      nil)
                  level-effects/enqueue-level-effect! (fn [effect-id payload fx-context]
                                                        (swap! enqueued* conj [effect-id payload fx-context])
                                                        nil)
                  client-sounds/queue-current-sound-effect! (fn [payload]
                                                              (swap! sounds* conj payload)
                                                              nil)]
      (rb-fx/init!)
      (@handler* "ctx-rb" :ray-barrage/fx-beam {:start {:x 1.0 :y 2.0 :z 3.0}
                      :end {:x 4.0 :y 5.0 :z 6.0}
                                                  :effect-instance-id "inst-rb"
                                                  :source-player-id "player-a"
                                                  :world-id "world-a"})
      (is (= [[:ray-barrage {:effect-instance-id "inst-rb"
                             :source-player-id "player-a"
                             :world-id "world-a"
                             :from-x 1.0 :from-y 2.0 :from-z 3.0
                             :to-x 4.0 :to-y 5.0 :to-z 6.0}
               {:ctx-id "ctx-rb" :channel :ray-barrage/fx-beam}]]
             @enqueued*))
      (is (empty? @sounds*)))))

(deftest fx-handler-preray-and-barrage-play-throttled-sounds-test
  (let [handler* (atom nil)
        sounds* (atom [])]
    (with-redefs [level-effects/register-level-effect! (fn [& _] nil)
                  fx-registry/register-fx-channels! (fn [_ handler]
                                                      (reset! handler* handler)
                                                      nil)
                  level-effects/enqueue-level-effect! (fn [& _] nil)
                  client-sounds/queue-current-sound-effect! (fn [payload]
                                                              (swap! sounds* conj payload)
                                                              nil)]
      (rb-fx/init!)
      (@handler* "ctx-pre" :ray-barrage/fx-preray {:start {:x 0.0 :y 0.0 :z 0.0}
                                                     :end {:x 1.0 :y 0.0 :z 0.0}})
      (@handler* "ctx-bar" :ray-barrage/fx-barrage {:silbarn {:x 2.0 :y 0.0 :z 0.0}
                                                      :scatter-count 3})
      (is (= 2 (count @sounds*)))
      (is (= [0.95 1.1] (map :pitch @sounds*))))))

(deftest fx-handler-supports-legacy-origin-beam-end-payload-test
  (let [handler* (atom nil)
        enqueued* (atom [])]
    (with-redefs [level-effects/register-level-effect! (fn [& _] nil)
                  fx-registry/register-fx-channels! (fn [_ handler]
                                                      (reset! handler* handler)
                                                      nil)
                  level-effects/enqueue-level-effect! (fn [effect-id payload fx-context]
                                                        (swap! enqueued* conj [effect-id payload fx-context])
                                                        nil)
                  client-sounds/queue-current-sound-effect! (fn [& _] nil)]
      (rb-fx/init!)
      (@handler* "ctx-rb-legacy" :ray-barrage/fx-beam {:origin {:x 1.0 :y 2.0 :z 3.0}
                                                         :beam-end {:x 4.0 :y 5.0 :z 6.0}})
      (is (= 1 (count @enqueued*)))
      (is (= {:from-x 1.0 :from-y 2.0 :from-z 3.0
              :to-x 4.0 :to-y 5.0 :to-z 6.0}
             (select-keys (second (first @enqueued*))
                          [:from-x :from-y :from-z :to-x :to-y :to-z]))))))

(deftest enqueue-beam-tick-and-build-plan-test
  (let [enqueue-state! (var-get #'cn.li.ac.content.ability.meltdowner.ray-barrage-fx/enqueue-state!)
        tick-state! (var-get #'cn.li.ac.content.ability.meltdowner.ray-barrage-fx/tick-state!)
        build-plan (var-get #'cn.li.ac.content.ability.meltdowner.ray-barrage-fx/build-plan)]
    (with-redefs [client-sounds/queue-current-sound-effect! (fn [& _] nil)]
      (level-effects/update-effect-state! :ray-barrage
        enqueue-state!
        (event "ctx-a" :ray-barrage/fx-beam
               {:from-x 1.0 :from-y 64.0 :from-z 2.0
                :to-x 4.0 :to-y 64.0 :to-z 6.0
                :source-player-id "player-a"
                :world-id "world-a"}))
      (is (some? (get-in (rb-fx/ray-barrage-fx-snapshot) [:beam-queue [:ctx "ctx-a"]])))
      (is (seq (:ops (build-plan {:x 0.0 :y 65.0 :z 0.0} nil 0))))
      (dotimes [_ 12]
        (level-effects/update-effect-state! :ray-barrage
          (fn [store _]
            (tick-state! store))
          nil))
      (is (nil? (build-plan {:x 0.0 :y 65.0 :z 0.0} nil 0)))
      (is (empty? (:beam-queue (rb-fx/ray-barrage-fx-snapshot)))))))

(deftest beam-alpha-fades-with-ttl-test
  (let [enqueue-state! (var-get #'cn.li.ac.content.ability.meltdowner.ray-barrage-fx/enqueue-state!)
        tick-state! (var-get #'cn.li.ac.content.ability.meltdowner.ray-barrage-fx/tick-state!)
        build-plan (var-get #'cn.li.ac.content.ability.meltdowner.ray-barrage-fx/build-plan)]
    (with-redefs [client-sounds/queue-current-sound-effect! (fn [& _] nil)]
      (level-effects/update-effect-state! :ray-barrage
        enqueue-state!
        (event "ctx-fade" :ray-barrage/fx-beam
               {:from-x 1.0 :from-y 64.0 :from-z 2.0
                :to-x 4.0 :to-y 64.0 :to-z 6.0
                :source-player-id "player-a"
                :world-id "world-a"}))

      (is (= 12 (get-in (rb-fx/ray-barrage-fx-snapshot) [:beam-queue [:ctx "ctx-fade"] 0 :ttl])))
      (level-effects/update-effect-state! :ray-barrage
        (fn [store _]
          (tick-state! store))
        nil)
      (is (= 11 (get-in (rb-fx/ray-barrage-fx-snapshot) [:beam-queue [:ctx "ctx-fade"] 0 :ttl]))
          "beam ttl should deterministically decay by one tick")
      (is (seq (:ops (build-plan nil nil 1))))

      (dotimes [_ 11]
        (level-effects/update-effect-state! :ray-barrage
          (fn [store _]
            (tick-state! store))
          nil))

      (is (nil? (build-plan nil nil 13)))
      (is (empty? (:beam-queue (rb-fx/ray-barrage-fx-snapshot)))))))

(deftest ray-barrage-fx-runtime-isolation-test
  (let [runtime-a (level-effects/create-level-effect-runtime)
        runtime-b (level-effects/create-level-effect-runtime)
        enqueue-state! (var-get #'cn.li.ac.content.ability.meltdowner.ray-barrage-fx/enqueue-state!)]
    (with-redefs [client-sounds/queue-current-sound-effect! (fn [& _] nil)]
      (level-effects/call-with-level-effect-runtime
        runtime-a
        (fn []
          (level-effects/update-effect-state! :ray-barrage
            enqueue-state!
            (event "ctx-a" :ray-barrage/fx-beam
                   {:from-x 1.0 :from-y 64.0 :from-z 2.0
                    :to-x 4.0 :to-y 64.0 :to-z 6.0
                    :source-player-id "player-a"}))
          (is (= #{[:ctx "ctx-a"]}
                 (set (keys (:beam-queue (rb-fx/ray-barrage-fx-snapshot))))))))
      (level-effects/call-with-level-effect-runtime
        runtime-b
        (fn []
          (is (= {:beam-queue {}}
                 (rb-fx/ray-barrage-fx-snapshot)))
          (level-effects/update-effect-state! :ray-barrage
            enqueue-state!
            (event "ctx-b" :ray-barrage/fx-beam
                   {:from-x 10.0 :from-y 64.0 :from-z 2.0
                    :to-x 14.0 :to-y 64.0 :to-z 6.0
                    :source-player-id "player-b"}))
          (is (= #{[:ctx "ctx-b"]}
                 (set (keys (:beam-queue (rb-fx/ray-barrage-fx-snapshot))))))))
      (level-effects/call-with-level-effect-runtime
        runtime-a
        (fn []
          (is (= #{[:ctx "ctx-a"]}
                 (set (keys (:beam-queue (rb-fx/ray-barrage-fx-snapshot)))))))))))

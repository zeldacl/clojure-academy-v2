(ns cn.li.ac.content.ability.meltdowner.ray-barrage-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.content.ability.meltdowner.ray-barrage-fx :as rb-fx]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(defn- reset-fixture [f]
  (runtime-hooks/with-client-ctx {:session-id :test-session}
    (try
          (level-effects/reset-level-effect-registry-for-test!)
          (rb-fx/reset-ray-barrage-fx-for-test!)
          (f)
          (finally
            (rb-fx/reset-ray-barrage-fx-for-test!)
            (level-effects/reset-level-effect-registry-for-test!)))))

(use-fixtures :each reset-fixture)

(defn- event
  [ctx-id channel payload]
  {:payload payload
   :ctx-id ctx-id
   :channel channel
   :owner-key [:ctx ctx-id]})

(deftest init-registers-owner-aware-ray-barrage-fx-test
  (let [registered-level* (atom nil)
        registered-topics* (atom #{})]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                         (reset! registered-level* [effect-id effect-map])
                                                         nil)
                  fx-registry/register-fx-channel! (fn [topic _handler]
                                                      (swap! registered-topics* conj topic)
                                                      nil)]
      (rb-fx/init!)
      (is (= :ray-barrage (first @registered-level*)))
      (is (fn? (:enqueue-state-fn (second @registered-level*))))
      (is (= #{:ray-barrage/fx-preray :ray-barrage/fx-barrage :ray-barrage/fx-beam}
             @registered-topics*)))))

(deftest fx-handler-routes-ray-barrage-beam-test
  (let [handlers* (atom {})
        enqueued* (atom [])
        sounds* (atom [])]
    (with-redefs [level-effects/register-level-effect! (fn [& _] nil)
                  fx-registry/register-fx-channel! (fn [topic handler]
                                                      (swap! handlers* assoc topic handler)
                                                      nil)
                  level-effects/enqueue-level-effect! (fn [effect-id ctx-id channel payload & opts]
                                                        (swap! enqueued* conj [effect-id ctx-id channel payload opts])
                                                        nil)
                  client-sounds/queue-current-sound-effect! (fn [payload]
                                                              (swap! sounds* conj payload)
                                                              nil)]
      (rb-fx/init!)
      ((get @handlers* :ray-barrage/fx-beam) "ctx-rb" :ray-barrage/fx-beam {:start {:x 1.0 :y 2.0 :z 3.0}
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
  (let [handlers* (atom {})
        sounds* (atom [])]
    (with-redefs [level-effects/register-level-effect! (fn [& _] nil)
                  fx-registry/register-fx-channel! (fn [topic handler]
                                                      (swap! handlers* assoc topic handler)
                                                      nil)
                  level-effects/enqueue-level-effect! (fn [& _] nil)
                  client-sounds/queue-current-sound-effect! (fn [payload]
                                                              (swap! sounds* conj payload)
                                                              nil)]
      (rb-fx/init!)
      ((get @handlers* :ray-barrage/fx-preray) "ctx-pre" :ray-barrage/fx-preray {:start {:x 0.0 :y 0.0 :z 0.0}
                                                     :end {:x 1.0 :y 0.0 :z 0.0}})
      ((get @handlers* :ray-barrage/fx-barrage) "ctx-bar" :ray-barrage/fx-barrage {:silbarn {:x 2.0 :y 0.0 :z 0.0}
                                                      :scatter-count 3})
      (is (= 2 (count @sounds*)))
      (is (= [0.95 1.1] (map :pitch @sounds*))))))

(deftest fx-handler-supports-legacy-origin-beam-end-payload-test
  (let [handlers* (atom {})
        enqueued* (atom [])]
    (with-redefs [level-effects/register-level-effect! (fn [& _] nil)
                  fx-registry/register-fx-channel! (fn [topic handler]
                                                      (swap! handlers* assoc topic handler)
                                                      nil)
                  level-effects/enqueue-level-effect! (fn [effect-id ctx-id channel payload & opts]
                                                        (swap! enqueued* conj [effect-id ctx-id channel payload opts])
                                                        nil)
                  client-sounds/queue-current-sound-effect! (fn [& _] nil)]
      (rb-fx/init!)
      ((get @handlers* :ray-barrage/fx-beam) "ctx-rb-legacy" :ray-barrage/fx-beam {:origin {:x 1.0 :y 2.0 :z 3.0}
                                                         :beam-end {:x 4.0 :y 5.0 :z 6.0}})
      (is (= 1 (count @enqueued*)))
      (is (= {:from-x 1.0 :from-y 2.0 :from-z 3.0
              :to-x 4.0 :to-y 5.0 :to-z 6.0}
             (select-keys (second (first @enqueued*))
                          [:from-x :from-y :from-z :to-x :to-y :to-z]))))))

(deftest enqueue-beam-tick-and-build-plan-test
  (do
    (with-redefs [client-sounds/queue-current-sound-effect! (fn [& _] nil)]
      (arc-beam/enqueue-for-test! :ray-barrage "ctx-a" :ray-barrage/fx-beam
               {:from-x 1.0 :from-y 64.0 :from-z 2.0
                :to-x 4.0 :to-y 64.0 :to-z 6.0
                :source-player-id "player-a"
                :world-id "world-a"})
      (is (some? (get-in (rb-fx/ray-barrage-fx-snapshot) [:beam-queue [:ctx "ctx-a"]])))
      (is (seq (:ops (arc-beam/effect-build-plan :ray-barrage {:x 0.0 :y 65.0 :z 0.0} nil 0))))
      (dotimes [_ 12]
        (level-effects/update-effect-state! :ray-barrage
          (fn [store] (arc-beam/effect-tick-state! :level :ray-barrage store))
          nil))
      (is (nil? (arc-beam/effect-build-plan :ray-barrage {:x 0.0 :y 65.0 :z 0.0} nil 0)))
      (is (empty? (:beam-queue (rb-fx/ray-barrage-fx-snapshot)))))))

(deftest beam-alpha-fades-with-ttl-test
  (do
    (with-redefs [client-sounds/queue-current-sound-effect! (fn [& _] nil)]
      (arc-beam/enqueue-for-test! :ray-barrage "ctx-fade" :ray-barrage/fx-beam
               {:from-x 1.0 :from-y 64.0 :from-z 2.0
                :to-x 4.0 :to-y 64.0 :to-z 6.0
                :source-player-id "player-a"
                :world-id "world-a"})

      (is (= 12 (get-in (rb-fx/ray-barrage-fx-snapshot) [:beam-queue [:ctx "ctx-fade"] 0 :ttl])))
      (level-effects/update-effect-state! :ray-barrage
        (fn [store] (arc-beam/effect-tick-state! :level :ray-barrage store))
        nil)
      (is (= 11 (get-in (rb-fx/ray-barrage-fx-snapshot) [:beam-queue [:ctx "ctx-fade"] 0 :ttl]))
          "beam ttl should deterministically decay by one tick")
      (is (seq (:ops (arc-beam/effect-build-plan :ray-barrage nil nil 1))))

      (dotimes [_ 11]
        (level-effects/update-effect-state! :ray-barrage
          (fn [store] (arc-beam/effect-tick-state! :level :ray-barrage store))
          nil))

      (is (nil? (arc-beam/effect-build-plan :ray-barrage nil nil 13)))
      (is (empty? (:beam-queue (rb-fx/ray-barrage-fx-snapshot)))))))



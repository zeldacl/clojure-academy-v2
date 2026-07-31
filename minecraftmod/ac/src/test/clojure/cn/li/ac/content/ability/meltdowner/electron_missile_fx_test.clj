(ns cn.li.ac.content.ability.meltdowner.electron-missile-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.content.ability.meltdowner.electron-missile-fx :as em-fx]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(defn- reset-fixture [f]
  (runtime-hooks/with-client-ctx-fn {:session-id :test-session} (fn [] (try
          (level-effects/reset-level-effect-registry-for-test!)
          (em-fx/reset-electron-missile-fx-for-test!)
          (f)
          (finally
            (em-fx/reset-electron-missile-fx-for-test!)
            (level-effects/reset-level-effect-registry-for-test!))))))

(use-fixtures :each reset-fixture)

;; ElectronMissile has no arc-beam impl — it owns its enqueue/tick fns and
;; registers them through fx-spec, so tests drive those directly.
(defn- enqueue!
  [enqueue-state! ctx-id channel payload]
  (level-effects/update-effect-state! :electron-missile
    (fn [store] (enqueue-state! store ctx-id channel [:ctx ctx-id] payload)))
  nil)

(defn- tick!
  [tick-state!]
  (level-effects/update-effect-state! :electron-missile
    (fn [store] (tick-state! store)))
  nil)

(deftest init-registers-owner-aware-electron-missile-fx-test
  (let [registered-level* (atom nil)
        registered-topics* (atom #{})]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                         (reset! registered-level* [effect-id effect-map])
                                                         nil)
                  fx-registry/register-fx-channel! (fn [topic _handler]
                                                      (swap! registered-topics* conj topic)
                                                      nil)]
      (em-fx/init!)
      (is (= :electron-missile (first @registered-level*)))
      (is (fn? (:enqueue-state-fn (second @registered-level*))))
      (is (= #{:electron-missile/fx-start
               :electron-missile/fx-update
               :electron-missile/fx-end
               :electron-missile/fx-fire}
             @registered-topics*)))))

(deftest fx-handler-routes-events-with-ctx-metadata-test
  (let [handlers* (atom {})
        enqueued* (atom [])]
    (with-redefs [level-effects/register-level-effect! (fn [& _] nil)
                  fx-registry/register-fx-channel! (fn [topic handler]
                                                      (swap! handlers* assoc topic handler)
                                                      nil)
                  level-effects/enqueue-level-effect! (fn [effect-id ctx-id channel payload & opts]
                                                        (swap! enqueued* conj [effect-id ctx-id channel payload opts])
                                                        nil)]
      (em-fx/init!)
      ((get @handlers* :electron-missile/fx-start) "ctx-em" :electron-missile/fx-start {:source-player-id "player-a"})
      ((get @handlers* :electron-missile/fx-update) "ctx-em" :electron-missile/fx-update {:ticks 12
                                                        :balls 3
                                                        :source-player-id "player-a"})
      ((get @handlers* :electron-missile/fx-fire) "ctx-em" :electron-missile/fx-fire {:target-x 1.0
                                                      :target-y 64.0
                                                      :target-z 2.0
                                                      :start {:x 0.0 :y 64.0 :z 0.0}
                                                      :end {:x 1.0 :y 65.5 :z 2.0}
                                                      :source-player-id "player-a"})
      ((get @handlers* :electron-missile/fx-end) "ctx-em" :electron-missile/fx-end {:source-player-id "player-a"})
      (is (= [[:electron-missile "ctx-em" :electron-missile/fx-start
               {:mode :start :source-player-id "player-a"}
               [:owner-key [:ctx "ctx-em"]]]
              [:electron-missile "ctx-em" :electron-missile/fx-update
               {:mode :update :ticks 12 :balls 3 :source-player-id "player-a"}
               [:owner-key [:ctx "ctx-em"]]]
              [:electron-missile "ctx-em" :electron-missile/fx-fire
               {:mode :fire
                :start {:x 0.0 :y 64.0 :z 0.0}
                :end {:x 1.0 :y 65.5 :z 2.0}
                :target-x 1.0
                :target-y 64.0
                :target-z 2.0
                :source-player-id "player-a"}
               [:owner-key [:ctx "ctx-em"]]]
              [:electron-missile "ctx-em" :electron-missile/fx-end
               {:mode :end :source-player-id "player-a"}
               [:owner-key [:ctx "ctx-em"]]]]
             @enqueued*)))))

(deftest fire-adds-beam-and-end-clears-state-test
  (let [enqueue-state! (var-get #'cn.li.ac.content.ability.meltdowner.electron-missile-fx/enqueue-state!)
        tick-state! (var-get #'cn.li.ac.content.ability.meltdowner.electron-missile-fx/tick-state!)
        particles* (atom [])
        sounds* (atom [])]
    (with-redefs [client-particles/current-effect-owner (fn [] {:client-session-id "electron-missile-fx-test"})
                  client-particles/queue-particle-effect! (fn [& args]
                                                             (swap! particles* conj args)
                                                             nil)
                  client-sounds/queue-sound-effect! (fn [& args]
                                                        (swap! sounds* conj args)
                                                        nil)]
      (enqueue! enqueue-state! "ctx-a" :electron-missile/fx-update
                {:mode :update
                 :ticks 8
                 :balls 2
                 :source-player-id "player-a"})
      (is (= 2 (get-in (em-fx/electron-missile-fx-snapshot) [:charge-state [:ctx "ctx-a"] :balls])))
      (enqueue! enqueue-state! "ctx-a" :electron-missile/fx-fire
                {:mode :fire
                 :start {:x 0.0 :y 64.0 :z 0.0}
                 :end {:x 1.0 :y 65.5 :z 2.0}
                 :target-x 1.0 :target-y 64.0 :target-z 2.0
                 :source-player-id "player-a"})
      (is (seq (get-in (em-fx/electron-missile-fx-snapshot) [:beams [:ctx "ctx-a"]])))
      (dotimes [_ 10]
        (tick! tick-state!))
      (is (empty? (get-in (em-fx/electron-missile-fx-snapshot) [:beams [:ctx "ctx-a"]])))
      (enqueue! enqueue-state! "ctx-a" :electron-missile/fx-end
                {:mode :end
                 :source-player-id "player-a"})
      (is (nil? (get-in (em-fx/electron-missile-fx-snapshot) [:charge-state [:ctx "ctx-a"]])))
      (is (seq @particles*))
      (is (seq @sounds*)))))

(deftest beam-impact-ttl-cadence-test
  (let [enqueue-state! (var-get #'cn.li.ac.content.ability.meltdowner.electron-missile-fx/enqueue-state!)
        tick-state! (var-get #'cn.li.ac.content.ability.meltdowner.electron-missile-fx/tick-state!)
        build-plan (var-get #'cn.li.ac.content.ability.meltdowner.electron-missile-fx/build-plan)
        particles* (atom [])]
    (with-redefs [client-particles/current-effect-owner (fn [] {:client-session-id "electron-missile-fx-test"})
                  client-particles/queue-particle-effect! (fn [& args]
                                                            (swap! particles* conj args)
                                                            nil)
                  client-sounds/queue-sound-effect! (fn [& _] nil)
                  ;; beam ops roll (rand) against a flicker threshold, so an
                  ;; unpinned rand drops both beams outright ~16% of runs
                  rand (fn ([] 0.0) ([n] (* 0.0 n)))]
      (enqueue! enqueue-state! "ctx-em" :electron-missile/fx-fire
                {:mode :fire
                 :start {:x 0.0 :y 64.0 :z 0.0}
                 :end {:x 1.0 :y 65.5 :z 2.0}
                 :source-player-id "player-a"})
      (enqueue! enqueue-state! "ctx-em" :electron-missile/fx-fire
                {:mode :fire
                 :target-x 4.0 :target-y 64.0 :target-z 4.0
                 :source-player-id "player-a"})

      (is (= 10 (get-in (em-fx/electron-missile-fx-snapshot) [:beams [:ctx "ctx-em"] 0 :ttl])))
      (is (= 10 (get-in (em-fx/electron-missile-fx-snapshot) [:impacts [:ctx "ctx-em"] 0 :ttl])))

      (tick! tick-state!)

      (is (= 9 (get-in (em-fx/electron-missile-fx-snapshot) [:beams [:ctx "ctx-em"] 0 :ttl])))
      (is (= 9 (get-in (em-fx/electron-missile-fx-snapshot) [:impacts [:ctx "ctx-em"] 0 :ttl])))
      (is (some? (build-plan nil nil 0 nil)))
      (is (= 2 (count @particles*))
          "one impact spark and one beam-end spark should be emitted per tick while both entries are alive")

      (dotimes [_ 9]
        (tick! tick-state!))

      (is (nil? (get-in (em-fx/electron-missile-fx-snapshot) [:beams [:ctx "ctx-em"]])))
      (is (nil? (get-in (em-fx/electron-missile-fx-snapshot) [:impacts [:ctx "ctx-em"]])))
      (is (nil? (build-plan nil nil 0 nil))))))

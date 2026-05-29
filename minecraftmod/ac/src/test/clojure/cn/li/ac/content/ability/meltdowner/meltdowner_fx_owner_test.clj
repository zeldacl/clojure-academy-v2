(ns cn.li.ac.content.ability.meltdowner.meltdowner-fx-owner-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.content.ability.meltdowner.electron-bomb-fx :as electron-bomb-fx]
            [cn.li.ac.content.ability.meltdowner.electron-missile-fx :as electron-missile-fx]
            [cn.li.ac.content.ability.meltdowner.jet-engine-fx :as jet-engine-fx]
            [cn.li.ac.content.ability.meltdowner.light-shield-fx :as light-shield-fx]
            [cn.li.ac.content.ability.meltdowner.meltdowner-fx :as meltdowner-fx]
            [cn.li.ac.content.ability.meltdowner.mine-ray-fx :as mine-ray-fx]
            [cn.li.ac.content.ability.meltdowner.ray-barrage-fx :as ray-barrage-fx]
            [cn.li.ac.content.ability.meltdowner.scatter-bomb-fx :as scatter-bomb-fx]))

(defn- reset-fixture [f]
  (level-effects/call-with-level-effect-runtime
    (level-effects/create-level-effect-runtime)
    (fn []
      (electron-bomb-fx/init!)
      (electron-missile-fx/init!)
      (jet-engine-fx/init!)
      (light-shield-fx/init!)
      (meltdowner-fx/init!)
      (mine-ray-fx/init!)
      (ray-barrage-fx/init!)
      (scatter-bomb-fx/init!)
      (electron-bomb-fx/reset-electron-bomb-fx-for-test!)
      (electron-missile-fx/reset-electron-missile-fx-for-test!)
      (jet-engine-fx/reset-jet-engine-fx-for-test!)
      (light-shield-fx/reset-light-shield-fx-for-test!)
      (meltdowner-fx/reset-meltdowner-fx-for-test!)
      (mine-ray-fx/reset-mine-ray-fx-for-test!)
      (ray-barrage-fx/reset-ray-barrage-fx-for-test!)
      (scatter-bomb-fx/reset-scatter-bomb-fx-for-test!)
      (try
        (f)
        (finally
          (electron-bomb-fx/reset-electron-bomb-fx-for-test!)
          (electron-missile-fx/reset-electron-missile-fx-for-test!)
          (jet-engine-fx/reset-jet-engine-fx-for-test!)
          (light-shield-fx/reset-light-shield-fx-for-test!)
          (meltdowner-fx/reset-meltdowner-fx-for-test!)
          (mine-ray-fx/reset-mine-ray-fx-for-test!)
          (ray-barrage-fx/reset-ray-barrage-fx-for-test!)
          (scatter-bomb-fx/reset-scatter-bomb-fx-for-test!))))))

(use-fixtures :each reset-fixture)

(defn- event [ctx-id channel payload]
  {:payload payload
   :ctx-id ctx-id
   :channel channel
   :owner-key [:ctx ctx-id]})

(defn- dispatch! [effect-id {:keys [payload ctx-id channel owner-key]}]
  (level-effects/enqueue-level-effect! effect-id payload
                                       {:ctx-id ctx-id :channel channel :owner-key owner-key}))

(def ^:private p0 {:x 0.0 :y 64.0 :z 0.0})
(def ^:private p1 {:x 1.0 :y 64.0 :z 0.0})
(def ^:private p2 {:x 2.0 :y 64.0 :z 0.0})

(deftest meltdowner-stateful-fx-keep-state-per-owner-test
  (with-redefs [client-particles/queue-particle-effect! (fn [& _] nil)
                client-particles/queue-current-particle-effect! (fn [& _] nil)
                client-particles/current-effect-owner (fn [] {:client-session-id "meltdowner-fx-owner"})
                client-sounds/queue-sound-effect! (fn [& _] nil)
                client-sounds/queue-current-sound-effect! (fn [& _] nil)
                client-sounds/current-effect-owner (fn [] {:client-session-id "meltdowner-fx-owner"})]
    (dispatch! :electron-bomb (event "ctx-a" :electron-bomb/fx-spawn
                                    {:mode :spawn :x 1.0 :y 64.0 :z 1.0}))
    (dispatch! :electron-bomb (event "ctx-b" :electron-bomb/fx-spawn
                                    {:mode :spawn :x 2.0 :y 64.0 :z 2.0}))

    (dispatch! :electron-missile (event "ctx-a" :electron-missile/fx-fire
                                       {:mode :fire :target-x 1.0 :target-y 64.0 :target-z 1.0}))
    (dispatch! :electron-missile (event "ctx-b" :electron-missile/fx-fire
                                       {:mode :fire :target-x 2.0 :target-y 64.0 :target-z 2.0}))

    (dispatch! :jet-engine (event "ctx-a" :jet-engine/fx-launch {:mode :launch :speed 1.5}))
    (dispatch! :jet-engine (event "ctx-b" :jet-engine/fx-launch {:mode :launch :speed 2.5}))

    (dispatch! :light-shield (event "ctx-a" :light-shield/fx-start {:mode :start}))
    (dispatch! :light-shield (event "ctx-b" :light-shield/fx-start {:mode :start}))

    (dispatch! :meltdowner (event "ctx-a" :meltdowner/fx-start {:mode :start}))
    (dispatch! :meltdowner (event "ctx-b" :meltdowner/fx-start {:mode :start}))
    (dispatch! :meltdowner (event "ctx-a" :meltdowner/fx-update
                                {:mode :update :ticks 12 :charge-ratio 0.4}))
    (dispatch! :meltdowner (event "ctx-b" :meltdowner/fx-update
                                {:mode :update :ticks 21 :charge-ratio 0.8}))
    (dispatch! :meltdowner (event "ctx-a" :meltdowner/fx-perform
                                {:mode :perform :start p0 :end p1}))
    (dispatch! :meltdowner (event "ctx-b" :meltdowner/fx-reflect
                                {:mode :reflect :start p1 :end p2}))

    (dispatch! :mine-ray (event "ctx-a" :mine-ray/fx-start {:mode :start :variant :basic}))
    (dispatch! :mine-ray (event "ctx-b" :mine-ray/fx-start {:mode :start :variant :expert}))
    (dispatch! :mine-ray (event "ctx-a" :mine-ray/fx-progress
                              {:mode :progress :x 1 :y 64 :z 1 :progress 0.25}))
    (dispatch! :mine-ray (event "ctx-b" :mine-ray/fx-progress
                              {:mode :progress :x 2 :y 64 :z 2 :progress 0.75}))

    (dispatch! :ray-barrage (event "ctx-a" :ray-barrage/fx-beam
                                 {:from-x 0.0 :from-y 64.0 :from-z 0.0 :to-x 1.0 :to-y 64.0 :to-z 0.0}))
    (dispatch! :ray-barrage (event "ctx-b" :ray-barrage/fx-beam
                                 {:from-x 0.0 :from-y 65.0 :from-z 0.0 :to-x 1.0 :to-y 65.0 :to-z 0.0}))

    (dispatch! :scatter-bomb (event "ctx-a" :scatter-bomb/fx-start {:mode :start}))
    (dispatch! :scatter-bomb (event "ctx-b" :scatter-bomb/fx-start {:mode :start}))
    (dispatch! :scatter-bomb (event "ctx-a" :scatter-bomb/fx-ball
                                  {:mode :ball :x 1.0 :y 64.0 :z 1.0 :count 3}))
    (dispatch! :scatter-bomb (event "ctx-b" :scatter-bomb/fx-ball
                                  {:mode :ball :x 2.0 :y 64.0 :z 2.0 :count 5}))

    (is (= 1.0 (get-in (electron-bomb-fx/electron-bomb-fx-snapshot) [:effect-state [:ctx "ctx-a"] :x])))
    (is (= 2.0 (get-in (electron-bomb-fx/electron-bomb-fx-snapshot) [:effect-state [:ctx "ctx-b"] :x])))
    (is (= 1 (count (get-in (electron-missile-fx/electron-missile-fx-snapshot) [:impacts [:ctx "ctx-a"]]))))
    (is (= 1 (count (get-in (electron-missile-fx/electron-missile-fx-snapshot) [:impacts [:ctx "ctx-b"]]))))
    (is (= 1.5 (get-in (jet-engine-fx/jet-engine-fx-snapshot) [:fx-state [:ctx "ctx-a"] :speed])))
    (is (= 2.5 (get-in (jet-engine-fx/jet-engine-fx-snapshot) [:fx-state [:ctx "ctx-b"] :speed])))
    (is (:active? (get-in (light-shield-fx/light-shield-fx-snapshot) [:effect-state [:ctx "ctx-a"]])))
    (is (:active? (get-in (light-shield-fx/light-shield-fx-snapshot) [:effect-state [:ctx "ctx-b"]])))
    (is (= 12 (get-in (meltdowner-fx/meltdowner-fx-snapshot) [:effect-state [:ctx "ctx-a"] :ticks])))
    (is (= 21 (get-in (meltdowner-fx/meltdowner-fx-snapshot) [:effect-state [:ctx "ctx-b"] :ticks])))
    (is (= 1 (count (get-in (meltdowner-fx/meltdowner-fx-snapshot) [:rays [:ctx "ctx-a"]]))))
    (is (= 1 (count (get-in (meltdowner-fx/meltdowner-fx-snapshot) [:rays [:ctx "ctx-b"]]))))
    (is (= 0.25 (get-in (mine-ray-fx/mine-ray-fx-snapshot) [:effect-state [:ctx "ctx-a"] :progress])))
    (is (= 0.75 (get-in (mine-ray-fx/mine-ray-fx-snapshot) [:effect-state [:ctx "ctx-b"] :progress])))
    (is (= 1 (count (get-in (ray-barrage-fx/ray-barrage-fx-snapshot) [:beam-queue [:ctx "ctx-a"]]))))
    (is (= 1 (count (get-in (ray-barrage-fx/ray-barrage-fx-snapshot) [:beam-queue [:ctx "ctx-b"]]))))
    (is (= 3 (get-in (scatter-bomb-fx/scatter-bomb-fx-snapshot) [:effect-state [:ctx "ctx-a"] :balls])))
    (is (= 5 (get-in (scatter-bomb-fx/scatter-bomb-fx-snapshot) [:effect-state [:ctx "ctx-b"] :balls])))))

(deftest meltdowner-fx-level-runtime-isolation-test
  (let [runtime-a (level-effects/create-level-effect-runtime)
        runtime-b (level-effects/create-level-effect-runtime)]
    (with-redefs [client-particles/queue-particle-effect! (fn [& _] nil)
                  client-particles/current-effect-owner (fn [] {:client-session-id "meltdowner-fx-owner"})
                  client-sounds/queue-sound-effect! (fn [& _] nil)
                  client-sounds/current-effect-owner (fn [] {:client-session-id "meltdowner-fx-owner"})]
      (level-effects/call-with-level-effect-runtime
        runtime-a
        (fn []
          (electron-bomb-fx/init!)
          (electron-bomb-fx/reset-electron-bomb-fx-for-test!)
          (dispatch! :electron-bomb (event "ctx-a" :electron-bomb/fx-spawn
                                         {:mode :spawn :x 1.0 :y 64.0 :z 1.0}))
          (is (= #{[:ctx "ctx-a"]}
                 (set (keys (:effect-state (electron-bomb-fx/electron-bomb-fx-snapshot))))))))

      (level-effects/call-with-level-effect-runtime
        runtime-b
        (fn []
          (electron-bomb-fx/init!)
          (electron-bomb-fx/reset-electron-bomb-fx-for-test!)
          (is (= {:effect-state {} :beams {}}
                 (electron-bomb-fx/electron-bomb-fx-snapshot)))
          (dispatch! :electron-bomb (event "ctx-b" :electron-bomb/fx-spawn
                                         {:mode :spawn :x 2.0 :y 64.0 :z 2.0}))
          (is (= #{[:ctx "ctx-b"]}
                 (set (keys (:effect-state (electron-bomb-fx/electron-bomb-fx-snapshot))))))))

      (level-effects/call-with-level-effect-runtime
        runtime-a
        (fn []
          (is (= #{[:ctx "ctx-a"]}
                 (set (keys (:effect-state (electron-bomb-fx/electron-bomb-fx-snapshot)))))))))))

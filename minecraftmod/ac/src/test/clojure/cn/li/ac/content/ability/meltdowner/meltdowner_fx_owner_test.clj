(ns cn.li.ac.content.ability.meltdowner.meltdowner-fx-owner-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.content.ability.meltdowner.electron-bomb-fx :as electron-bomb-fx]
            [cn.li.ac.content.ability.meltdowner.electron-missile-fx :as electron-missile-fx]
            [cn.li.ac.content.ability.meltdowner.jet-engine-fx :as jet-engine-fx]
            [cn.li.ac.content.ability.meltdowner.light-shield-fx :as light-shield-fx]
            [cn.li.ac.content.ability.meltdowner.meltdowner-fx :as meltdowner-fx]
            [cn.li.ac.content.ability.meltdowner.mine-ray-fx :as mine-ray-fx]
            [cn.li.ac.content.ability.meltdowner.ray-barrage-fx :as ray-barrage-fx]
            [cn.li.ac.content.ability.meltdowner.scatter-bomb-fx :as scatter-bomb-fx]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(defn- reset-fixture [f]
  (binding [runtime-hooks/*client-session-id* :test-session
            electron-bomb-fx/*electron-bomb-fx-runtime* (electron-bomb-fx/create-electron-bomb-fx-runtime)
            electron-missile-fx/*electron-missile-fx-runtime* (electron-missile-fx/create-electron-missile-fx-runtime)
            jet-engine-fx/*jet-engine-fx-runtime* (jet-engine-fx/create-jet-engine-fx-runtime)
            light-shield-fx/*light-shield-fx-runtime* (light-shield-fx/create-light-shield-fx-runtime)
            meltdowner-fx/*meltdowner-fx-runtime* (meltdowner-fx/create-meltdowner-fx-runtime)
            mine-ray-fx/*mine-ray-fx-runtime* (mine-ray-fx/create-mine-ray-fx-runtime)
            ray-barrage-fx/*ray-barrage-fx-runtime* (ray-barrage-fx/create-ray-barrage-fx-runtime)
            scatter-bomb-fx/*scatter-bomb-fx-runtime* (scatter-bomb-fx/create-scatter-bomb-fx-runtime)]
    (electron-bomb-fx/reset-electron-bomb-fx-for-test!)
    (electron-missile-fx/reset-electron-missile-fx-for-test!)
    (jet-engine-fx/reset-jet-engine-fx-for-test!)
    (light-shield-fx/reset-light-shield-fx-for-test!)
    (meltdowner-fx/reset-meltdowner-fx-for-test!)
    (mine-ray-fx/reset-mine-ray-fx-for-test!)
    (ray-barrage-fx/reset-ray-barrage-fx-for-test!)
    (scatter-bomb-fx/reset-scatter-bomb-fx-for-test!)
    (f)
    (electron-bomb-fx/reset-electron-bomb-fx-for-test!)
    (electron-missile-fx/reset-electron-missile-fx-for-test!)
    (jet-engine-fx/reset-jet-engine-fx-for-test!)
    (light-shield-fx/reset-light-shield-fx-for-test!)
    (meltdowner-fx/reset-meltdowner-fx-for-test!)
    (mine-ray-fx/reset-mine-ray-fx-for-test!)
    (ray-barrage-fx/reset-ray-barrage-fx-for-test!)
    (scatter-bomb-fx/reset-scatter-bomb-fx-for-test!)))

(use-fixtures :each reset-fixture)

(defn- event [ctx-id channel payload]
  {:payload payload
   :ctx-id ctx-id
   :channel channel
   :owner-key [:ctx ctx-id]})

(def ^:private p0 {:x 0.0 :y 64.0 :z 0.0})
(def ^:private p1 {:x 1.0 :y 64.0 :z 0.0})
(def ^:private p2 {:x 2.0 :y 64.0 :z 0.0})

(deftest meltdowner-stateful-fx-keep-state-per-owner-test
  (let [electron-bomb-enqueue! (var-get #'cn.li.ac.content.ability.meltdowner.electron-bomb-fx/enqueue!)
        electron-missile-enqueue! (var-get #'cn.li.ac.content.ability.meltdowner.electron-missile-fx/enqueue!)
        jet-engine-enqueue! (var-get #'cn.li.ac.content.ability.meltdowner.jet-engine-fx/enqueue!)
        light-shield-enqueue! (var-get #'cn.li.ac.content.ability.meltdowner.light-shield-fx/enqueue!)
        meltdowner-enqueue! (var-get #'cn.li.ac.content.ability.meltdowner.meltdowner-fx/enqueue!)
        mine-ray-enqueue! (var-get #'cn.li.ac.content.ability.meltdowner.mine-ray-fx/enqueue!)
        ray-barrage-enqueue! (var-get #'cn.li.ac.content.ability.meltdowner.ray-barrage-fx/enqueue-beam!)
        scatter-bomb-enqueue! (var-get #'cn.li.ac.content.ability.meltdowner.scatter-bomb-fx/enqueue!)]
    (with-redefs [client-particles/queue-particle-effect! (fn [& _] nil)
                  client-sounds/queue-sound-effect! (fn [& _] nil)]
      (electron-bomb-enqueue! (event "ctx-a" :electron-bomb/fx-spawn
                                     {:mode :spawn :x 1.0 :y 64.0 :z 1.0}))
      (electron-bomb-enqueue! (event "ctx-b" :electron-bomb/fx-spawn
                                     {:mode :spawn :x 2.0 :y 64.0 :z 2.0}))

      (electron-missile-enqueue! (event "ctx-a" :electron-missile/fx-fire
                                        {:mode :fire :target-x 1.0 :target-y 64.0 :target-z 1.0}))
      (electron-missile-enqueue! (event "ctx-b" :electron-missile/fx-fire
                                        {:mode :fire :target-x 2.0 :target-y 64.0 :target-z 2.0}))

      (jet-engine-enqueue! (event "ctx-a" :jet-engine/fx-launch {:mode :launch :speed 1.5}))
      (jet-engine-enqueue! (event "ctx-b" :jet-engine/fx-launch {:mode :launch :speed 2.5}))

      (light-shield-enqueue! (event "ctx-a" :light-shield/fx-start {:mode :start}))
      (light-shield-enqueue! (event "ctx-b" :light-shield/fx-start {:mode :start}))

      (meltdowner-enqueue! (event "ctx-a" :meltdowner/fx-start {:mode :start}))
      (meltdowner-enqueue! (event "ctx-b" :meltdowner/fx-start {:mode :start}))
      (meltdowner-enqueue! (event "ctx-a" :meltdowner/fx-update
                                  {:mode :update :ticks 12 :charge-ratio 0.4}))
      (meltdowner-enqueue! (event "ctx-b" :meltdowner/fx-update
                                  {:mode :update :ticks 21 :charge-ratio 0.8}))
      (meltdowner-enqueue! (event "ctx-a" :meltdowner/fx-perform
                                  {:mode :perform :start p0 :end p1}))
      (meltdowner-enqueue! (event "ctx-b" :meltdowner/fx-reflect
                                  {:mode :reflect :start p1 :end p2}))

      (mine-ray-enqueue! (event "ctx-a" :mine-ray/fx-start {:mode :start :variant :basic}))
      (mine-ray-enqueue! (event "ctx-b" :mine-ray/fx-start {:mode :start :variant :expert}))
      (mine-ray-enqueue! (event "ctx-a" :mine-ray/fx-progress
                                {:mode :progress :x 1 :y 64 :z 1 :progress 0.25}))
      (mine-ray-enqueue! (event "ctx-b" :mine-ray/fx-progress
                                {:mode :progress :x 2 :y 64 :z 2 :progress 0.75}))

      (ray-barrage-enqueue! (event "ctx-a" :ray-barrage/fx-beam
                                   {:from-x 0.0 :from-y 64.0 :from-z 0.0 :to-x 1.0 :to-y 64.0 :to-z 0.0}))
      (ray-barrage-enqueue! (event "ctx-b" :ray-barrage/fx-beam
                                   {:from-x 0.0 :from-y 65.0 :from-z 0.0 :to-x 1.0 :to-y 65.0 :to-z 0.0}))

      (scatter-bomb-enqueue! (event "ctx-a" :scatter-bomb/fx-start {:mode :start}))
      (scatter-bomb-enqueue! (event "ctx-b" :scatter-bomb/fx-start {:mode :start}))
      (scatter-bomb-enqueue! (event "ctx-a" :scatter-bomb/fx-ball
                                    {:mode :ball :x 1.0 :y 64.0 :z 1.0 :count 3}))
      (scatter-bomb-enqueue! (event "ctx-b" :scatter-bomb/fx-ball
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
      (is (= 5 (get-in (scatter-bomb-fx/scatter-bomb-fx-snapshot) [:effect-state [:ctx "ctx-b"] :balls])))

      (electron-bomb-fx/clear-electron-bomb-owner! [:ctx "ctx-a"])
      (electron-missile-fx/clear-electron-missile-owner! [:ctx "ctx-a"])
      (jet-engine-fx/clear-jet-engine-owner! [:ctx "ctx-a"])
      (light-shield-fx/clear-light-shield-owner! [:ctx "ctx-a"])
      (meltdowner-fx/clear-meltdowner-owner! [:ctx "ctx-a"])
      (mine-ray-fx/clear-mine-ray-owner! [:ctx "ctx-a"])
      (ray-barrage-fx/clear-ray-barrage-owner! [:ctx "ctx-a"])
      (scatter-bomb-fx/clear-scatter-bomb-owner! [:ctx "ctx-a"])

      (is (nil? (get-in (electron-bomb-fx/electron-bomb-fx-snapshot) [:effect-state [:ctx "ctx-a"]])))
      (is (some? (get-in (electron-bomb-fx/electron-bomb-fx-snapshot) [:effect-state [:ctx "ctx-b"]])))
      (is (nil? (get-in (electron-missile-fx/electron-missile-fx-snapshot) [:impacts [:ctx "ctx-a"]])))
      (is (some? (get-in (electron-missile-fx/electron-missile-fx-snapshot) [:impacts [:ctx "ctx-b"]])))
      (is (nil? (get-in (jet-engine-fx/jet-engine-fx-snapshot) [:fx-state [:ctx "ctx-a"]])))
      (is (some? (get-in (jet-engine-fx/jet-engine-fx-snapshot) [:fx-state [:ctx "ctx-b"]])))
      (is (nil? (get-in (light-shield-fx/light-shield-fx-snapshot) [:effect-state [:ctx "ctx-a"]])))
      (is (some? (get-in (light-shield-fx/light-shield-fx-snapshot) [:effect-state [:ctx "ctx-b"]])))
      (is (nil? (get-in (meltdowner-fx/meltdowner-fx-snapshot) [:effect-state [:ctx "ctx-a"]])))
      (is (some? (get-in (meltdowner-fx/meltdowner-fx-snapshot) [:effect-state [:ctx "ctx-b"]])))
      (is (nil? (get-in (mine-ray-fx/mine-ray-fx-snapshot) [:effect-state [:ctx "ctx-a"]])))
      (is (some? (get-in (mine-ray-fx/mine-ray-fx-snapshot) [:effect-state [:ctx "ctx-b"]])))
      (is (nil? (get-in (ray-barrage-fx/ray-barrage-fx-snapshot) [:beam-queue [:ctx "ctx-a"]])))
      (is (some? (get-in (ray-barrage-fx/ray-barrage-fx-snapshot) [:beam-queue [:ctx "ctx-b"]])))
      (is (nil? (get-in (scatter-bomb-fx/scatter-bomb-fx-snapshot) [:effect-state [:ctx "ctx-a"]])))
      (is (some? (get-in (scatter-bomb-fx/scatter-bomb-fx-snapshot) [:effect-state [:ctx "ctx-b"]]))))))

(deftest electron-bomb-fx-runtime-isolation-test
  (let [runtime-a (electron-bomb-fx/create-electron-bomb-fx-runtime)
        runtime-b (electron-bomb-fx/create-electron-bomb-fx-runtime)
        enqueue! (var-get #'cn.li.ac.content.ability.meltdowner.electron-bomb-fx/enqueue!)]
    (with-redefs [client-particles/queue-particle-effect! (fn [& _] nil)
                  client-sounds/queue-sound-effect! (fn [& _] nil)]
      (electron-bomb-fx/call-with-electron-bomb-fx-runtime
        runtime-a
        (fn []
          (enqueue! (event "ctx-a" :electron-bomb/fx-spawn
                           {:mode :spawn :x 1.0 :y 64.0 :z 1.0}))
          (is (= #{[:ctx "ctx-a"]}
                 (set (keys (:effect-state (electron-bomb-fx/electron-bomb-fx-snapshot))))))))
      (electron-bomb-fx/call-with-electron-bomb-fx-runtime
        runtime-b
        (fn []
          (is (= {:effect-state {}}
                 (electron-bomb-fx/electron-bomb-fx-snapshot)))
          (enqueue! (event "ctx-b" :electron-bomb/fx-spawn
                           {:mode :spawn :x 2.0 :y 64.0 :z 2.0}))
          (is (= #{[:ctx "ctx-b"]}
                 (set (keys (:effect-state (electron-bomb-fx/electron-bomb-fx-snapshot))))))))
      (electron-bomb-fx/call-with-electron-bomb-fx-runtime
        runtime-a
        (fn []
          (is (= #{[:ctx "ctx-a"]}
                 (set (keys (:effect-state (electron-bomb-fx/electron-bomb-fx-snapshot)))))))))))

(deftest electron-missile-fx-runtime-isolation-test
  (let [runtime-a (electron-missile-fx/create-electron-missile-fx-runtime)
        runtime-b (electron-missile-fx/create-electron-missile-fx-runtime)
        enqueue! (var-get #'cn.li.ac.content.ability.meltdowner.electron-missile-fx/enqueue!)]
    (with-redefs [client-particles/queue-particle-effect! (fn [& _] nil)
                  client-sounds/queue-sound-effect! (fn [& _] nil)]
      (electron-missile-fx/call-with-electron-missile-fx-runtime
        runtime-a
        (fn []
          (enqueue! (event "ctx-a" :electron-missile/fx-fire
                           {:mode :fire :target-x 1.0 :target-y 64.0 :target-z 1.0}))
          (is (= #{[:ctx "ctx-a"]}
                 (set (keys (:impacts (electron-missile-fx/electron-missile-fx-snapshot))))))))
      (electron-missile-fx/call-with-electron-missile-fx-runtime
        runtime-b
        (fn []
          (is (= {:impacts {}}
                 (electron-missile-fx/electron-missile-fx-snapshot)))
          (enqueue! (event "ctx-b" :electron-missile/fx-fire
                           {:mode :fire :target-x 2.0 :target-y 64.0 :target-z 2.0}))
          (is (= #{[:ctx "ctx-b"]}
                 (set (keys (:impacts (electron-missile-fx/electron-missile-fx-snapshot))))))))
      (electron-missile-fx/call-with-electron-missile-fx-runtime
        runtime-a
        (fn []
          (is (= #{[:ctx "ctx-a"]}
                 (set (keys (:impacts (electron-missile-fx/electron-missile-fx-snapshot)))))))))))

(deftest jet-engine-fx-runtime-isolation-test
  (let [runtime-a (jet-engine-fx/create-jet-engine-fx-runtime)
        runtime-b (jet-engine-fx/create-jet-engine-fx-runtime)
        enqueue! (var-get #'cn.li.ac.content.ability.meltdowner.jet-engine-fx/enqueue!)]
    (with-redefs [client-sounds/queue-current-sound-effect! (fn [& _] nil)]
      (jet-engine-fx/call-with-jet-engine-fx-runtime
        runtime-a
        (fn []
          (enqueue! (event "ctx-a" :jet-engine/fx-launch {:mode :launch :speed 1.5}))
          (is (= #{[:ctx "ctx-a"]}
                 (set (keys (:fx-state (jet-engine-fx/jet-engine-fx-snapshot))))))))
      (jet-engine-fx/call-with-jet-engine-fx-runtime
        runtime-b
        (fn []
          (is (= {:fx-state {}}
                 (jet-engine-fx/jet-engine-fx-snapshot)))
          (enqueue! (event "ctx-b" :jet-engine/fx-launch {:mode :launch :speed 2.5}))
          (is (= #{[:ctx "ctx-b"]}
                 (set (keys (:fx-state (jet-engine-fx/jet-engine-fx-snapshot))))))))
      (jet-engine-fx/call-with-jet-engine-fx-runtime
        runtime-a
        (fn []
          (is (= #{[:ctx "ctx-a"]}
                 (set (keys (:fx-state (jet-engine-fx/jet-engine-fx-snapshot)))))))))))

(deftest light-shield-fx-runtime-isolation-test
  (let [runtime-a (light-shield-fx/create-light-shield-fx-runtime)
        runtime-b (light-shield-fx/create-light-shield-fx-runtime)
        enqueue! (var-get #'cn.li.ac.content.ability.meltdowner.light-shield-fx/enqueue!)]
    (with-redefs [client-particles/queue-particle-effect! (fn [& _] nil)
                  client-sounds/queue-sound-effect! (fn [& _] nil)]
      (light-shield-fx/call-with-light-shield-fx-runtime
        runtime-a
        (fn []
          (enqueue! (event "ctx-a" :light-shield/fx-start {:mode :start}))
          (is (= #{[:ctx "ctx-a"]}
                 (set (keys (:effect-state (light-shield-fx/light-shield-fx-snapshot))))))))
      (light-shield-fx/call-with-light-shield-fx-runtime
        runtime-b
        (fn []
          (is (= {:effect-state {}}
                 (light-shield-fx/light-shield-fx-snapshot)))
          (enqueue! (event "ctx-b" :light-shield/fx-start {:mode :start}))
          (is (= #{[:ctx "ctx-b"]}
                 (set (keys (:effect-state (light-shield-fx/light-shield-fx-snapshot))))))))
      (light-shield-fx/call-with-light-shield-fx-runtime
        runtime-a
        (fn []
          (is (= #{[:ctx "ctx-a"]}
                 (set (keys (:effect-state (light-shield-fx/light-shield-fx-snapshot)))))))))))

(deftest meltdowner-fx-runtime-isolation-test
  (let [runtime-a (meltdowner-fx/create-meltdowner-fx-runtime)
        runtime-b (meltdowner-fx/create-meltdowner-fx-runtime)
        enqueue! (var-get #'cn.li.ac.content.ability.meltdowner.meltdowner-fx/enqueue!)]
    (with-redefs [client-sounds/queue-sound-effect! (fn [& _] nil)]
      (meltdowner-fx/call-with-meltdowner-fx-runtime
        runtime-a
        (fn []
          (enqueue! (event "ctx-a" :meltdowner/fx-start {:mode :start}))
          (is (= #{[:ctx "ctx-a"]}
                 (set (keys (:effect-state (meltdowner-fx/meltdowner-fx-snapshot))))))))
      (meltdowner-fx/call-with-meltdowner-fx-runtime
        runtime-b
        (fn []
          (is (= {:effect-state {}
                  :rays {}}
                 (meltdowner-fx/meltdowner-fx-snapshot)))
          (enqueue! (event "ctx-b" :meltdowner/fx-start {:mode :start}))
          (is (= #{[:ctx "ctx-b"]}
                 (set (keys (:effect-state (meltdowner-fx/meltdowner-fx-snapshot))))))))
      (meltdowner-fx/call-with-meltdowner-fx-runtime
        runtime-a
        (fn []
          (is (= #{[:ctx "ctx-a"]}
                 (set (keys (:effect-state (meltdowner-fx/meltdowner-fx-snapshot)))))))))))

(deftest mine-ray-fx-runtime-isolation-test
  (let [runtime-a (mine-ray-fx/create-mine-ray-fx-runtime)
        runtime-b (mine-ray-fx/create-mine-ray-fx-runtime)
        enqueue! (var-get #'cn.li.ac.content.ability.meltdowner.mine-ray-fx/enqueue!)]
    (with-redefs [client-particles/queue-particle-effect! (fn [& _] nil)
                  client-sounds/queue-sound-effect! (fn [& _] nil)]
      (mine-ray-fx/call-with-mine-ray-fx-runtime
        runtime-a
        (fn []
          (enqueue! (event "ctx-a" :mine-ray/fx-start {:mode :start :variant :basic}))
          (is (= #{[:ctx "ctx-a"]}
                 (set (keys (:effect-state (mine-ray-fx/mine-ray-fx-snapshot))))))))
      (mine-ray-fx/call-with-mine-ray-fx-runtime
        runtime-b
        (fn []
          (is (= {:effect-state {}}
                 (mine-ray-fx/mine-ray-fx-snapshot)))
          (enqueue! (event "ctx-b" :mine-ray/fx-start {:mode :start :variant :expert}))
          (is (= #{[:ctx "ctx-b"]}
                 (set (keys (:effect-state (mine-ray-fx/mine-ray-fx-snapshot))))))))
      (mine-ray-fx/call-with-mine-ray-fx-runtime
        runtime-a
        (fn []
          (is (= #{[:ctx "ctx-a"]}
                 (set (keys (:effect-state (mine-ray-fx/mine-ray-fx-snapshot)))))))))))

(deftest ray-barrage-fx-runtime-isolation-test
  (let [runtime-a (ray-barrage-fx/create-ray-barrage-fx-runtime)
        runtime-b (ray-barrage-fx/create-ray-barrage-fx-runtime)
        enqueue! (var-get #'cn.li.ac.content.ability.meltdowner.ray-barrage-fx/enqueue-beam!)]
    (ray-barrage-fx/call-with-ray-barrage-fx-runtime
      runtime-a
      (fn []
        (enqueue! (event "ctx-a" :ray-barrage/fx-beam
                         {:from-x 0.0 :from-y 64.0 :from-z 0.0 :to-x 1.0 :to-y 64.0 :to-z 0.0}))
        (is (= #{[:ctx "ctx-a"]}
               (set (keys (:beam-queue (ray-barrage-fx/ray-barrage-fx-snapshot))))))))
    (ray-barrage-fx/call-with-ray-barrage-fx-runtime
      runtime-b
      (fn []
        (is (= {:beam-queue {}}
               (ray-barrage-fx/ray-barrage-fx-snapshot)))
        (enqueue! (event "ctx-b" :ray-barrage/fx-beam
                         {:from-x 0.0 :from-y 65.0 :from-z 0.0 :to-x 1.0 :to-y 65.0 :to-z 0.0}))
        (is (= #{[:ctx "ctx-b"]}
               (set (keys (:beam-queue (ray-barrage-fx/ray-barrage-fx-snapshot))))))))
    (ray-barrage-fx/call-with-ray-barrage-fx-runtime
      runtime-a
      (fn []
        (is (= #{[:ctx "ctx-a"]}
               (set (keys (:beam-queue (ray-barrage-fx/ray-barrage-fx-snapshot))))))))))

(deftest scatter-bomb-fx-runtime-isolation-test
  (let [runtime-a (scatter-bomb-fx/create-scatter-bomb-fx-runtime)
        runtime-b (scatter-bomb-fx/create-scatter-bomb-fx-runtime)
        enqueue! (var-get #'cn.li.ac.content.ability.meltdowner.scatter-bomb-fx/enqueue!)]
    (with-redefs [client-particles/queue-current-particle-effect! (fn [& _] nil)
                  client-sounds/queue-current-sound-effect! (fn [& _] nil)]
      (scatter-bomb-fx/call-with-scatter-bomb-fx-runtime
        runtime-a
        (fn []
          (enqueue! (event "ctx-a" :scatter-bomb/fx-start {:mode :start}))
          (is (= #{[:ctx "ctx-a"]}
                 (set (keys (:effect-state (scatter-bomb-fx/scatter-bomb-fx-snapshot))))))))
      (scatter-bomb-fx/call-with-scatter-bomb-fx-runtime
        runtime-b
        (fn []
          (is (= {:effect-state {}}
                 (scatter-bomb-fx/scatter-bomb-fx-snapshot)))
          (enqueue! (event "ctx-b" :scatter-bomb/fx-start {:mode :start}))
          (is (= #{[:ctx "ctx-b"]}
                 (set (keys (:effect-state (scatter-bomb-fx/scatter-bomb-fx-snapshot))))))))
      (scatter-bomb-fx/call-with-scatter-bomb-fx-runtime
        runtime-a
        (fn []
          (is (= #{[:ctx "ctx-a"]}
                 (set (keys (:effect-state (scatter-bomb-fx/scatter-bomb-fx-snapshot)))))))))))

    (deftest meltdowner-fx-runtime-required-without-binding-test
      (binding [electron-bomb-fx/*electron-bomb-fx-runtime* nil
        electron-missile-fx/*electron-missile-fx-runtime* nil
        jet-engine-fx/*jet-engine-fx-runtime* nil
        light-shield-fx/*light-shield-fx-runtime* nil
        meltdowner-fx/*meltdowner-fx-runtime* nil
        mine-ray-fx/*mine-ray-fx-runtime* nil
        ray-barrage-fx/*ray-barrage-fx-runtime* nil
        scatter-bomb-fx/*scatter-bomb-fx-runtime* nil]
        (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"runtime is not bound"
          (electron-bomb-fx/electron-bomb-fx-snapshot)))
        (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"runtime is not bound"
          (electron-missile-fx/electron-missile-fx-snapshot)))
        (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"runtime is not bound"
          (jet-engine-fx/jet-engine-fx-snapshot)))
        (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"runtime is not bound"
          (light-shield-fx/light-shield-fx-snapshot)))
        (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"runtime is not bound"
          (meltdowner-fx/meltdowner-fx-snapshot)))
        (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"runtime is not bound"
          (mine-ray-fx/mine-ray-fx-snapshot)))
        (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"runtime is not bound"
          (ray-barrage-fx/ray-barrage-fx-snapshot)))
        (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"runtime is not bound"
          (scatter-bomb-fx/scatter-bomb-fx-snapshot)))))

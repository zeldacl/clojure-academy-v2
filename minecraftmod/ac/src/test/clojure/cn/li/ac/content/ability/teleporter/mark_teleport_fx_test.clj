(ns cn.li.ac.content.ability.teleporter.mark-teleport-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.content.ability.teleporter.mark-teleport-fx :as mfx]))

(defn- with-fresh-mark-teleport-fx-runtime [f]
  (level-effects/call-with-level-effect-runtime
    (level-effects/create-level-effect-runtime)
    (fn []
      (level-effects/reset-level-effect-registry-for-test!)
      (mfx/reset-mark-teleport-fx-for-test!)
      (try
        (f)
        (finally
          (mfx/reset-mark-teleport-fx-for-test!)
          (level-effects/reset-level-effect-registry-for-test!))))))

(use-fixtures :each with-fresh-mark-teleport-fx-runtime)

(deftest init-registers-mark-teleport-fx-channels-test
  (let [registered-level* (atom nil)
        registered-topics* (atom #{})]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                         (reset! registered-level* [effect-id effect-map])
                                                         nil)
                  fx-registry/register-fx-channel! (fn [topic _handler]
                                                      (swap! registered-topics* conj topic)
                                                      nil)]
      (mfx/init!)
      (is (= :mark-teleport (first @registered-level*)))
      (is (= #{:mark-teleport/fx-start
               :mark-teleport/fx-update
               :mark-teleport/fx-perform
               :mark-teleport/fx-end}
             @registered-topics*)))))

(deftest enqueue-perform-with-target-emits-particles-and-sound-test
  (let [particles* (atom [])
        sounds* (atom [])]
    (with-redefs [client-particles/current-effect-owner (fn [] {:client-session-id "mark-teleport-test"})
                  client-particles/queue-particle-effect! (fn [& args]
                                                            (swap! particles* conj args)
                                                            nil)
                  client-sounds/queue-sound-effect! (fn [& args]
                                                      (swap! sounds* conj args)
                                                      nil)]
      (mfx/init!)
      (level-effects/enqueue-level-effect! :mark-teleport {:mode :perform :target {:x 2.0 :y 64.0 :z 3.0} :distance 8.0}
                                           {:ctx-id "ctx-1" :channel :mark-teleport/fx-perform :owner-key [:ctx "ctx-1"]})
      (is (= 1 (count @particles*)))
      (is (= 1 (count @sounds*)))
      (is (= "my_mod:tp.tp" (:sound-id (second (first @sounds*))))))))

(deftest enqueue-end-clears-state-test
  (with-redefs [client-particles/current-effect-owner (fn [] {:client-session-id "mark-teleport-test"})]
    (mfx/init!)
    (level-effects/enqueue-level-effect! :mark-teleport {:mode :start}
                                         {:ctx-id "ctx-1" :channel :mark-teleport/fx-start :owner-key [:ctx "ctx-1"]})
    (level-effects/enqueue-level-effect! :mark-teleport {:mode :update :target {:x 1.0 :y 2.0 :z 3.0} :distance 2.0}
                                         {:ctx-id "ctx-1" :channel :mark-teleport/fx-update :owner-key [:ctx "ctx-1"]})
    (is (some? (get (:effect-state (mfx/mark-teleport-fx-snapshot)) [:ctx "ctx-1"])))
    (level-effects/enqueue-level-effect! :mark-teleport {:mode :end}
                                         {:ctx-id "ctx-1" :channel :mark-teleport/fx-end :owner-key [:ctx "ctx-1"]})
    (is (nil? (get (:effect-state (mfx/mark-teleport-fx-snapshot)) [:ctx "ctx-1"])))))

(deftest mark-teleport-fx-runtime-isolation-test
  (let [runtime-a (level-effects/create-level-effect-runtime)
        runtime-b (level-effects/create-level-effect-runtime)]
    (with-redefs [client-particles/current-effect-owner (fn [] {:client-session-id "mark-teleport-test"})
                  client-particles/queue-particle-effect! (fn [& _] nil)
                  client-sounds/queue-sound-effect! (fn [& _] nil)]
      (level-effects/call-with-level-effect-runtime runtime-a
        (fn []
          (mfx/init!)
          (level-effects/enqueue-level-effect! :mark-teleport {:mode :start}
                                               {:ctx-id "ctx-a" :channel :mark-teleport/fx-start :owner-key [:ctx "ctx-a"]})
          (is (= #{[:ctx "ctx-a"]}
                 (set (keys (:effect-state (mfx/mark-teleport-fx-snapshot))))))))
      (level-effects/call-with-level-effect-runtime runtime-b
        (fn []
          (mfx/init!)
          (is (= {:effect-state {}}
                 (mfx/mark-teleport-fx-snapshot)))
          (level-effects/enqueue-level-effect! :mark-teleport {:mode :start}
                                               {:ctx-id "ctx-b" :channel :mark-teleport/fx-start :owner-key [:ctx "ctx-b"]})
          (is (= #{[:ctx "ctx-b"]}
                 (set (keys (:effect-state (mfx/mark-teleport-fx-snapshot))))))))
      (level-effects/call-with-level-effect-runtime runtime-a
        (fn []
          (is (= #{[:ctx "ctx-a"]}
                 (set (keys (:effect-state (mfx/mark-teleport-fx-snapshot)))))))))))

(deftest mark-teleport-fx-snapshot-default-without-registered-state-test
  (is (= {:effect-state {}}
         (mfx/mark-teleport-fx-snapshot))))

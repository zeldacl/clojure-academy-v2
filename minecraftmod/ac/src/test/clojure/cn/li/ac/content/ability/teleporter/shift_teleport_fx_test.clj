(ns cn.li.ac.content.ability.teleporter.shift-teleport-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.content.ability.teleporter.shift-teleport-fx :as stfx]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]))

(defn- with-fresh-shift-teleport-fx-runtime [f]
  (level-effects/reset-level-effect-registry-for-test!)
  (stfx/reset-fx-for-test!)
      (try
        (f)
        (finally
          (stfx/reset-fx-for-test!)
          (level-effects/reset-level-effect-registry-for-test!))))

(use-fixtures :each with-fresh-shift-teleport-fx-runtime)

(deftest init-registers-shift-teleport-fx-channels-test
  (let [registered-level* (atom nil)
        registered-topics* (atom #{})]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                         (reset! registered-level* [effect-id effect-map])
                                                         nil)
                  fx-registry/register-fx-channel! (fn [topic _handler]
                                                      (swap! registered-topics* conj topic)
                                                      nil)]
      (stfx/init!)
      (is (= :shift-teleport (first @registered-level*)))
      (is (= #{:shift-teleport/fx-start
               :shift-teleport/fx-update
               :shift-teleport/fx-perform
               :shift-teleport/fx-end}
             @registered-topics*)))))

(deftest enqueue-perform-emits-path-particles-and-sound-test
  (let [particles* (atom [])
        sounds* (atom [])]
    (with-redefs [client-bridge/run-client-effect! (fn [& _] nil)
                  client-particles/current-effect-owner (fn [] {:client-session-id "shift-teleport-test"})
                  client-particles/queue-particle-effect! (fn [& args]
                                                            (swap! particles* conj args)
                                                            nil)
                  client-sounds/queue-sound-effect! (fn [& args]
                                                      (swap! sounds* conj args)
                                                      nil)]
      (stfx/init!)
      (level-effects/enqueue-level-effect! :shift-teleport "ctx-1" :shift-teleport/fx-perform {:mode :perform :from-x 0.0 :from-y 64.0 :from-z 0.0 :x 5.0 :y 64.0 :z 0.0}
                                         :owner-key [:ctx "ctx-1"])
      (is (>= (count @particles*) 2))
      (is (= 1 (count @sounds*)))
      (is (= "my_mod:tp.tp" (:sound-id (second (first @sounds*))))))))

(deftest enqueue-end-clears-state-test
  (with-redefs [client-bridge/run-client-effect! (fn [& _] nil)
                client-particles/current-effect-owner (fn [] {:client-session-id "shift-teleport-test"})]
    (stfx/init!)
    (level-effects/enqueue-level-effect! :shift-teleport "ctx-1" :shift-teleport/fx-start {:mode :start}
                                         :owner-key [:ctx "ctx-1"])
    (level-effects/enqueue-level-effect! :shift-teleport "ctx-1" :shift-teleport/fx-update {:mode :update :x 1.0 :y 2.0 :z 3.0 :target-count 1 :target-hit? false :hand-valid? true}
                                         :owner-key [:ctx "ctx-1"])
    (is (some? (get (:fx-state (stfx/fx-snapshot)) [:ctx "ctx-1"])))
    (level-effects/enqueue-level-effect! :shift-teleport "ctx-1" :shift-teleport/fx-end {:mode :end}
                                         :owner-key [:ctx "ctx-1"])
    (is (nil? (get (:fx-state (stfx/fx-snapshot)) [:ctx "ctx-1"])))))



(deftest fx-snapshot-default-without-registered-state-test
  (is (= {:fx-state {}}
         (stfx/fx-snapshot))))

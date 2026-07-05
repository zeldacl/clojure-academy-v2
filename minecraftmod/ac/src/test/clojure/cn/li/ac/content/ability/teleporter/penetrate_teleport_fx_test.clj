(ns cn.li.ac.content.ability.teleporter.penetrate-teleport-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.content.ability.teleporter.penetrate-teleport-fx :as pfx]))

(defn- with-fresh-penetrate-teleport-fx-runtime [f]
  (level-effects/reset-level-effect-registry-for-test!)
  (pfx/reset-penetrate-teleport-fx-for-test!)
      (try
        (f)
        (finally
          (pfx/reset-penetrate-teleport-fx-for-test!)
          (level-effects/reset-level-effect-registry-for-test!))))

(use-fixtures :each with-fresh-penetrate-teleport-fx-runtime)

(deftest init-registers-penetrate-fx-channels-test
  (let [registered-level* (atom nil)
        registered-topics* (atom #{})]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                         (reset! registered-level* [effect-id effect-map])
                                                         nil)
                  fx-registry/register-fx-channel! (fn [topic _handler]
                                                      (swap! registered-topics* conj topic)
                                                      nil)]
      (pfx/init!)
      (is (= :penetrate-teleport (first @registered-level*)))
      (is (= #{:penetrate-teleport/fx-start
               :penetrate-teleport/fx-update
               :penetrate-teleport/fx-perform
               :penetrate-teleport/fx-end}
             @registered-topics*)))))

(deftest perform-and-tick-emit-particles-and-sound-test
  (let [particle-calls* (atom [])
        sound-calls* (atom [])]
    (with-redefs [client-particles/current-effect-owner (fn [] {:client-session-id "penetrate-teleport-test"})
                  client-particles/queue-particle-effect! (fn [& args]
                                                            (swap! particle-calls* conj args)
                                                            nil)
                  client-sounds/queue-sound-effect! (fn [& args]
                                                      (swap! sound-calls* conj args)
                                                      nil)]
      (pfx/init!)
      (level-effects/enqueue-level-effect! :penetrate-teleport "ctx-1" :penetrate-teleport/fx-start {:mode :start}
                                         :owner-key [:ctx "ctx-1"])
      (level-effects/enqueue-level-effect! :penetrate-teleport "ctx-1" :penetrate-teleport/fx-update {:mode :update :available? true :distance 12.0 :x 1.0 :y 2.0 :z 3.0}
                                         :owner-key [:ctx "ctx-1"])
      (dotimes [_ 3] (level-effects/tick-level-effects!))
      (level-effects/enqueue-level-effect! :penetrate-teleport "ctx-1" :penetrate-teleport/fx-perform {:mode :perform :x 4.0 :y 5.0 :z 6.0}
                                         :owner-key [:ctx "ctx-1"])
      (is (true? (get-in (pfx/penetrate-teleport-fx-snapshot) [:fx-state [:ctx "ctx-1"] :available?])))
      (is (= 2 (count @particle-calls*)))
      (is (= 1 (count @sound-calls*))))))



(deftest penetrate-teleport-fx-snapshot-default-without-registered-state-test
  (is (= {:fx-state {}}
         (pfx/penetrate-teleport-fx-snapshot))))

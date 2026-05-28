(ns cn.li.ac.content.ability.teleporter.penetrate-teleport-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.content.ability.teleporter.penetrate-teleport-fx :as pfx]))

(defn- with-fresh-penetrate-teleport-fx-runtime [f]
  (level-effects/call-with-level-effect-runtime
    (level-effects/create-level-effect-runtime)
    (fn []
      (level-effects/reset-level-effect-registry-for-test!)
      (pfx/reset-penetrate-teleport-fx-for-test!)
      (try
        (f)
        (finally
          (pfx/reset-penetrate-teleport-fx-for-test!)
          (level-effects/reset-level-effect-registry-for-test!))))))

(use-fixtures :each with-fresh-penetrate-teleport-fx-runtime)

(deftest init-registers-penetrate-fx-channels-test
  (let [registered-level* (atom nil)
        registered-handler* (atom nil)]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                         (reset! registered-level* [effect-id effect-map])
                                                         nil)
                  fx-registry/register-fx-channels! (fn [channels handler]
                                                      (reset! registered-handler* {:channels channels
                                                                                   :handler handler})
                                                      nil)]
      (pfx/init!)
      (is (= :penetrate-teleport (first @registered-level*)))
      (is (= #{:penetrate-tp/fx-start
               :penetrate-tp/fx-update
               :penetrate-tp/fx-perform
               :penetrate-tp/fx-end}
             (set (:channels @registered-handler*)))))))

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
      (level-effects/enqueue-level-effect! :penetrate-teleport {:mode :start}
                                           {:ctx-id "ctx-1" :channel :penetrate-tp/fx-test :owner-key [:ctx "ctx-1"]})
      (level-effects/enqueue-level-effect! :penetrate-teleport {:mode :update :available? true :distance 12.0 :x 1.0 :y 2.0 :z 3.0}
                                           {:ctx-id "ctx-1" :channel :penetrate-tp/fx-test :owner-key [:ctx "ctx-1"]})
      (dotimes [_ 3] (level-effects/tick-level-effects!))
      (level-effects/enqueue-level-effect! :penetrate-teleport {:mode :perform :x 4.0 :y 5.0 :z 6.0}
                                           {:ctx-id "ctx-1" :channel :penetrate-tp/fx-test :owner-key [:ctx "ctx-1"]})
      (is (true? (get-in (pfx/penetrate-teleport-fx-snapshot) [:fx-state [:ctx "ctx-1"] :available?])))
      (is (= 2 (count @particle-calls*)))
      (is (= 1 (count @sound-calls*))))))

(deftest penetrate-teleport-fx-runtime-isolation-test
  (let [runtime-a (level-effects/create-level-effect-runtime)
        runtime-b (level-effects/create-level-effect-runtime)]
    (with-redefs [client-particles/current-effect-owner (fn [] {:client-session-id "penetrate-teleport-test"})
                  client-particles/queue-particle-effect! (fn [& _] nil)
                  client-sounds/queue-sound-effect! (fn [& _] nil)]
      (level-effects/call-with-level-effect-runtime runtime-a
        (fn []
          (pfx/init!)
          (level-effects/enqueue-level-effect! :penetrate-teleport {:mode :start}
                                               {:ctx-id "ctx-a" :channel :penetrate-tp/fx-test :owner-key [:ctx "ctx-a"]})
          (is (= #{[:ctx "ctx-a"]}
                 (set (keys (:fx-state (pfx/penetrate-teleport-fx-snapshot))))))))
      (level-effects/call-with-level-effect-runtime runtime-b
        (fn []
          (pfx/init!)
          (is (= {:fx-state {}}
                 (pfx/penetrate-teleport-fx-snapshot)))
          (level-effects/enqueue-level-effect! :penetrate-teleport {:mode :start}
                                               {:ctx-id "ctx-b" :channel :penetrate-tp/fx-test :owner-key [:ctx "ctx-b"]})
          (is (= #{[:ctx "ctx-b"]}
                 (set (keys (:fx-state (pfx/penetrate-teleport-fx-snapshot))))))))
      (level-effects/call-with-level-effect-runtime runtime-a
        (fn []
          (is (= #{[:ctx "ctx-a"]}
                 (set (keys (:fx-state (pfx/penetrate-teleport-fx-snapshot)))))))))))

(deftest penetrate-teleport-fx-snapshot-default-without-registered-state-test
  (is (= {:fx-state {}}
         (pfx/penetrate-teleport-fx-snapshot))))

(ns cn.li.ac.content.ability.teleporter.penetrate-teleport-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
              [cn.li.ac.ability.client.effects.particles :as client-particles]
              [cn.li.ac.ability.client.effects.sounds :as client-sounds]
              [cn.li.ac.ability.client.fx-registry :as fx-registry]
              [cn.li.ac.ability.client.level-effects :as level-effects]
              [cn.li.ac.content.ability.teleporter.penetrate-teleport-fx :as pfx]))

  (defn- with-fresh-penetrate-teleport-fx-runtime [f]
    (pfx/call-with-penetrate-teleport-fx-runtime
      (pfx/create-penetrate-teleport-fx-runtime)
      (fn []
        (try
          (f)
          (finally
            (pfx/reset-penetrate-teleport-fx-for-test!))))))

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

(deftest fx-handler-routes-update-and-perform-payloads-test
  (let [handler* (atom nil)
        enqueued* (atom [])]
    (with-redefs [level-effects/register-level-effect! (fn [& _] nil)
                  fx-registry/register-fx-channels! (fn [_ handler]
                                                      (reset! handler* handler)
                                                      nil)
                  level-effects/enqueue-level-effect! (fn [effect-id payload fx-context]
                                                        (swap! enqueued* conj [effect-id payload fx-context])
                                                        nil)]
      (pfx/init!)
      (@handler* "ctx-1" :penetrate-tp/fx-start nil)
      (@handler* "ctx-1" :penetrate-tp/fx-update {:distance 12.0 :available? true :x 1.0 :y 2.0 :z 3.0})
      (@handler* "ctx-1" :penetrate-tp/fx-perform {:x 4.0 :y 5.0 :z 6.0})
      (@handler* "ctx-1" :penetrate-tp/fx-end nil)

            (is (= [[:penetrate-teleport {:mode :start} {:ctx-id "ctx-1" :channel :penetrate-tp/fx-start}]
              [:penetrate-teleport {:mode :update :distance 12.0 :available? true :x 1.0 :y 2.0 :z 3.0}
               {:ctx-id "ctx-1" :channel :penetrate-tp/fx-update}]
              [:penetrate-teleport {:mode :perform :x 4.0 :y 5.0 :z 6.0}
               {:ctx-id "ctx-1" :channel :penetrate-tp/fx-perform}]
              [:penetrate-teleport {:mode :end} {:ctx-id "ctx-1" :channel :penetrate-tp/fx-end}]]
             @enqueued*)))))

(deftest perform-and-tick-emit-particles-and-sound-test
  (let [enqueue! (var-get #'cn.li.ac.content.ability.teleporter.penetrate-teleport-fx/enqueue!)
        tick! (var-get #'cn.li.ac.content.ability.teleporter.penetrate-teleport-fx/tick!)
        particle-calls* (atom [])
        sound-calls* (atom [])]
    (with-redefs [client-particles/current-effect-owner (fn [] {:client-session-id "penetrate-teleport-test"})
                  client-particles/queue-particle-effect! (fn [& args]
                                                            (swap! particle-calls* conj args)
                                                            nil)
                  client-sounds/queue-sound-effect! (fn [& args]
                                                      (swap! sound-calls* conj args)
                                                      nil)]
      (enqueue! {:payload {:mode :start}
                 :ctx-id "ctx-1"
                 :channel :penetrate-tp/fx-test
                 :owner-key [:ctx "ctx-1"]})
      (enqueue! {:payload {:mode :update :available? true :distance 12.0 :x 1.0 :y 2.0 :z 3.0}
                 :ctx-id "ctx-1"
                 :channel :penetrate-tp/fx-test
                 :owner-key [:ctx "ctx-1"]})
      (dotimes [_ 3] (tick!))
      (enqueue! {:payload {:mode :perform :x 4.0 :y 5.0 :z 6.0}
                 :ctx-id "ctx-1"
                 :channel :penetrate-tp/fx-test
                 :owner-key [:ctx "ctx-1"]})
      (is (= true (get-in (pfx/penetrate-teleport-fx-snapshot) [:fx-state [:ctx "ctx-1"] :available?])))
      (is (= 2 (count @particle-calls*)))
      (is (= 1 (count @sound-calls*))))))

(deftest penetrate-teleport-fx-runtime-isolation-test
  (let [runtime-a (pfx/create-penetrate-teleport-fx-runtime)
        runtime-b (pfx/create-penetrate-teleport-fx-runtime)
        enqueue! (var-get #'cn.li.ac.content.ability.teleporter.penetrate-teleport-fx/enqueue!)]
    (with-redefs [client-particles/current-effect-owner (fn [] {:client-session-id "penetrate-teleport-test"})
                  client-particles/queue-particle-effect! (fn [& _] nil)
                  client-sounds/queue-sound-effect! (fn [& _] nil)]
      (pfx/call-with-penetrate-teleport-fx-runtime
        runtime-a
        (fn []
          (enqueue! {:payload {:mode :start}
                     :ctx-id "ctx-a"
                     :channel :penetrate-tp/fx-test
                     :owner-key [:ctx "ctx-a"]})
          (is (= #{[:ctx "ctx-a"]}
                 (set (keys (:fx-state (pfx/penetrate-teleport-fx-snapshot))))))))
      (pfx/call-with-penetrate-teleport-fx-runtime
        runtime-b
        (fn []
          (is (= {:fx-state {}}
                 (pfx/penetrate-teleport-fx-snapshot)))
          (enqueue! {:payload {:mode :start}
                     :ctx-id "ctx-b"
                     :channel :penetrate-tp/fx-test
                     :owner-key [:ctx "ctx-b"]})
          (is (= #{[:ctx "ctx-b"]}
                 (set (keys (:fx-state (pfx/penetrate-teleport-fx-snapshot))))))))
      (pfx/call-with-penetrate-teleport-fx-runtime
        runtime-a
        (fn []
          (is (= #{[:ctx "ctx-a"]}
                 (set (keys (:fx-state (pfx/penetrate-teleport-fx-snapshot)))))))))))

(deftest penetrate-teleport-fx-runtime-required-without-binding-test
  (binding [pfx/*penetrate-teleport-fx-runtime* nil]
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"runtime is not bound"
          (pfx/penetrate-teleport-fx-snapshot)))))

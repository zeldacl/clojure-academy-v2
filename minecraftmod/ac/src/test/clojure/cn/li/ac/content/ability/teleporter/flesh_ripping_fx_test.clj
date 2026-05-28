(ns cn.li.ac.content.ability.teleporter.flesh-ripping-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.content.ability.teleporter.flesh-ripping-fx :as frfx]))

(defn- with-fresh-flesh-ripping-fx-runtime [f]
  (frfx/call-with-flesh-ripping-fx-runtime
    (frfx/create-flesh-ripping-fx-runtime)
    (fn []
      (try
        (f)
        (finally
          (frfx/reset-flesh-ripping-fx-for-test!))))))

(use-fixtures :each with-fresh-flesh-ripping-fx-runtime)

(defn- event [payload]
  {:payload payload
   :ctx-id "ctx-1"
   :channel :flesh-ripping/fx-test
   :owner-key [:ctx "ctx-1"]})

(deftest init-registers-flesh-ripping-fx-channels-test
  (let [registered-level* (atom nil)
        registered-handler* (atom nil)]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                         (reset! registered-level* [effect-id effect-map])
                                                         nil)
                  fx-registry/register-fx-channels! (fn [channels handler]
                                                      (reset! registered-handler* {:channels channels
                                                                                   :handler handler})
                                                      nil)]
      (frfx/init!)
      (is (= :flesh-ripping (first @registered-level*)))
      (is (= #{:flesh-ripping/fx-start
               :flesh-ripping/fx-update
               :flesh-ripping/fx-perform
               :flesh-ripping/fx-end}
             (set (:channels @registered-handler*)))))))

(deftest fx-handler-routes-start-update-perform-end-payloads-test
  (let [handler* (atom nil)
        enqueued* (atom [])]
    (with-redefs [level-effects/register-level-effect! (fn [& _] nil)
                  fx-registry/register-fx-channels! (fn [_ handler]
                                                      (reset! handler* handler)
                                                      nil)
                  level-effects/enqueue-level-effect! (fn [effect-id payload fx-context]
                                                        (swap! enqueued* conj [effect-id payload fx-context])
                                                        nil)]
      (frfx/init!)
      (@handler* "ctx-1" :flesh-ripping/fx-start nil)
      (@handler* "ctx-1" :flesh-ripping/fx-update {:target-x 1.0 :target-y 2.0 :target-z 3.0 :hit? true :target-uuid "target-1"})
      (@handler* "ctx-1" :flesh-ripping/fx-perform {:target-x 4.0 :target-y 5.0 :target-z 6.0 :hit? true :target-uuid "target-2"})
      (@handler* "ctx-1" :flesh-ripping/fx-end nil)

            (is (= [[:flesh-ripping {:mode :start} {:ctx-id "ctx-1" :channel :flesh-ripping/fx-start}]
              [:flesh-ripping {:mode :update
                               :target-x 1.0
                               :target-y 2.0
                               :target-z 3.0
                               :hit? true
                   :target-uuid "target-1"}
               {:ctx-id "ctx-1" :channel :flesh-ripping/fx-update}]
              [:flesh-ripping {:mode :perform
                               :target-x 4.0
                               :target-y 5.0
                               :target-z 6.0
                               :hit? true
                   :target-uuid "target-2"}
               {:ctx-id "ctx-1" :channel :flesh-ripping/fx-perform}]
              [:flesh-ripping {:mode :end} {:ctx-id "ctx-1" :channel :flesh-ripping/fx-end}]]
             @enqueued*)))))

(deftest enqueue-perform-emits-particles-and-sound-test
  (let [particles* (atom [])
        sounds* (atom [])
        enqueue! (var-get #'cn.li.ac.content.ability.teleporter.flesh-ripping-fx/enqueue!)]
    (with-redefs [client-particles/current-effect-owner (fn [] {:client-session-id "flesh-ripping-test"})
                  client-particles/queue-particle-effect! (fn [& args]
                                                            (swap! particles* conj args)
                                                            nil)
                  client-sounds/queue-sound-effect! (fn [& args]
                                                      (swap! sounds* conj args)
                                                      nil)]
      (enqueue! (event {:mode :perform
                        :target-x 1.0 :target-y 2.0 :target-z 3.0
                        :hit? true
                        :target-uuid "target-1"}))
      (is (= 2 (count @particles*)))
      (is (= 1 (count @sounds*)))
      (is (= "my_mod:tp.flesh_ripping" (:sound-id (second (first @sounds*))))))))

(deftest enqueue-end-clears-state-test
  (let [enqueue! (var-get #'cn.li.ac.content.ability.teleporter.flesh-ripping-fx/enqueue!)]
    (with-redefs [client-particles/current-effect-owner (fn [] {:client-session-id "flesh-ripping-test"})]
      (enqueue! (event {:mode :start}))
      (enqueue! (event {:mode :update :target-x 1.0 :target-y 2.0 :target-z 3.0 :hit? false}))
      (is (some? (get (:fx-state (frfx/flesh-ripping-fx-snapshot)) [:ctx "ctx-1"])))
      (enqueue! (event {:mode :end}))
      (is (nil? (get (:fx-state (frfx/flesh-ripping-fx-snapshot)) [:ctx "ctx-1"]))))))

(deftest flesh-ripping-fx-runtime-isolation-test
  (let [runtime-a (frfx/create-flesh-ripping-fx-runtime)
        runtime-b (frfx/create-flesh-ripping-fx-runtime)
        enqueue! (var-get #'cn.li.ac.content.ability.teleporter.flesh-ripping-fx/enqueue!)]
    (with-redefs [client-particles/current-effect-owner (fn [] {:client-session-id "flesh-ripping-test"})
                  client-particles/queue-particle-effect! (fn [& _] nil)
                  client-sounds/queue-sound-effect! (fn [& _] nil)]
      (frfx/call-with-flesh-ripping-fx-runtime
        runtime-a
        (fn []
          (enqueue! {:payload {:mode :start}
                     :ctx-id "ctx-a"
                     :channel :flesh-ripping/fx-test
                     :owner-key [:ctx "ctx-a"]})
          (is (= #{[:ctx "ctx-a"]}
                 (set (keys (:fx-state (frfx/flesh-ripping-fx-snapshot))))))))
      (frfx/call-with-flesh-ripping-fx-runtime
        runtime-b
        (fn []
          (is (= {:fx-state {}}
                 (frfx/flesh-ripping-fx-snapshot)))
          (enqueue! {:payload {:mode :start}
                     :ctx-id "ctx-b"
                     :channel :flesh-ripping/fx-test
                     :owner-key [:ctx "ctx-b"]})
          (is (= #{[:ctx "ctx-b"]}
                 (set (keys (:fx-state (frfx/flesh-ripping-fx-snapshot))))))))
      (frfx/call-with-flesh-ripping-fx-runtime
        runtime-a
        (fn []
          (is (= #{[:ctx "ctx-a"]}
                 (set (keys (:fx-state (frfx/flesh-ripping-fx-snapshot)))))))))))

(deftest flesh-ripping-fx-runtime-required-without-binding-test
  (binding [frfx/*flesh-ripping-fx-runtime* nil]
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"runtime is not bound"
          (frfx/flesh-ripping-fx-snapshot)))))
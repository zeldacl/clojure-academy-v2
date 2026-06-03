(ns cn.li.ac.content.ability.teleporter.flashing-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.content.ability.teleporter.flashing-fx :as ffx]))

(defn- with-fresh-flashing-fx-runtime [f]
  (level-effects/call-with-level-effect-runtime
    (level-effects/create-level-effect-runtime)
    (fn []
      (level-effects/reset-level-effect-registry-for-test!)
      (ffx/reset-flashing-fx-for-test!)
      (try
        (f)
        (finally
          (ffx/reset-flashing-fx-for-test!)
          (level-effects/reset-level-effect-registry-for-test!))))))

(use-fixtures :each with-fresh-flashing-fx-runtime)

(deftest init-registers-flashing-fx-channels-test
  (let [registered-level* (atom nil)
        registered-handler* (atom nil)]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                         (reset! registered-level* [effect-id effect-map])
                                                         nil)
                  fx-registry/register-fx-channels! (fn [channels handler]
                                                      (reset! registered-handler* {:channels channels
                                                                                   :handler handler})
                                                      nil)]
      (ffx/init!)
      (is (= :flashing (first @registered-level*)))
      (is (= #{:flashing/fx-state-start
               :flashing/fx-preview-start
               :flashing/fx-preview-update
               :flashing/fx-preview-end
               :flashing/fx-perform
               :flashing/fx-state-end}
             (set (:channels @registered-handler*)))))))

(deftest fx-handler-routes-preview-and-perform-events-test
  (let [handler* (atom nil)
        enqueued* (atom [])]
    (with-redefs [level-effects/register-level-effect! (fn [& _] nil)
                  fx-registry/register-fx-channels! (fn [_ handler]
                                                      (reset! handler* handler)
                                                      nil)
                  level-effects/enqueue-level-effect! (fn [effect-id payload fx-context]
                                                        (swap! enqueued* conj [effect-id payload fx-context])
                                                        nil)]
      (ffx/init!)
      (@handler* "ctx-1" :flashing/fx-state-start nil)
      (@handler* "ctx-1" :flashing/fx-preview-start {:to-x 1.0 :to-y 64.0 :to-z 2.0})
      (@handler* "ctx-1" :flashing/fx-preview-update {:to-x 2.0 :to-y 64.0 :to-z 3.0})
      (@handler* "ctx-1" :flashing/fx-perform {:from-x 0.0 :from-y 64.0 :from-z 0.0
                                                :to-x 2.0 :to-y 64.0 :to-z 3.0})
      (@handler* "ctx-1" :flashing/fx-preview-end nil)
      (@handler* "ctx-1" :flashing/fx-state-end nil)
      (is (= [[:flashing {:mode :state-start} {:ctx-id "ctx-1" :channel :flashing/fx-state-start}]
              [:flashing {:mode :preview-start :to-x 1.0 :to-y 64.0 :to-z 2.0}
               {:ctx-id "ctx-1" :channel :flashing/fx-preview-start}]
              [:flashing {:mode :preview-update :to-x 2.0 :to-y 64.0 :to-z 3.0}
               {:ctx-id "ctx-1" :channel :flashing/fx-preview-update}]
              [:flashing {:mode :perform
                          :from-x 0.0 :from-y 64.0 :from-z 0.0
                          :to-x 2.0 :to-y 64.0 :to-z 3.0}
               {:ctx-id "ctx-1" :channel :flashing/fx-perform}]
              [:flashing {:mode :preview-end} {:ctx-id "ctx-1" :channel :flashing/fx-preview-end}]
              [:flashing {:mode :state-end} {:ctx-id "ctx-1" :channel :flashing/fx-state-end}]]
             @enqueued*)))))

(deftest enqueue-perform-emits-sound-and-particles-test
  (let [particles* (atom [])
        sounds* (atom [])]
    (with-redefs [client-particles/current-effect-owner (fn [] {:client-session-id "flashing-test"})
                  client-particles/queue-particle-effect! (fn [& args]
                                                            (swap! particles* conj args)
                                                            nil)
                  client-sounds/queue-sound-effect! (fn [& args]
                                                      (swap! sounds* conj args)
                                                      nil)]
      (ffx/init!)
      (level-effects/enqueue-level-effect! :flashing {:mode :state-start}
                                           {:ctx-id "ctx-1" :channel :flashing/fx-test :owner-key [:ctx "ctx-1"]})
      (level-effects/enqueue-level-effect! :flashing {:mode :preview-update :to-x 1.0 :to-y 64.0 :to-z 1.0}
                                           {:ctx-id "ctx-1" :channel :flashing/fx-test :owner-key [:ctx "ctx-1"]})
      (level-effects/enqueue-level-effect! :flashing {:mode :perform
                                                      :from-x 0.0 :from-y 64.0 :from-z 0.0
                                                      :to-x 2.0 :to-y 64.0 :to-z 2.0}
                                           {:ctx-id "ctx-1" :channel :flashing/fx-test :owner-key [:ctx "ctx-1"]})
      (level-effects/tick-level-effects!)
      (is (= 1 (count @sounds*)))
      (is (>= (count @particles*) 2))
      (is (= "my_mod:tp.tp_flashing" (:sound-id (second (first @sounds*))))))))

(deftest flashing-fx-runtime-isolation-test
  (let [runtime-a (level-effects/create-level-effect-runtime)
        runtime-b (level-effects/create-level-effect-runtime)]
    (with-redefs [client-particles/current-effect-owner (fn [] {:client-session-id "flashing-test"})
                  client-particles/queue-particle-effect! (fn [& _] nil)
                  client-sounds/queue-sound-effect! (fn [& _] nil)]
      (level-effects/call-with-level-effect-runtime runtime-a
        (fn []
          (ffx/init!)
          (level-effects/enqueue-level-effect! :flashing {:mode :state-start}
                                               {:ctx-id "ctx-a" :channel :flashing/fx-test :owner-key [:ctx "ctx-a"]})
          (level-effects/enqueue-level-effect! :flashing {:mode :preview-update :to-x 1.0 :to-y 64.0 :to-z 1.0}
                                               {:ctx-id "ctx-a" :channel :flashing/fx-test :owner-key [:ctx "ctx-a"]})
          (is (= #{[:ctx "ctx-a"]}
                 (set (keys (:fx-state (ffx/flashing-fx-snapshot))))))))
      (level-effects/call-with-level-effect-runtime runtime-b
        (fn []
          (ffx/init!)
          (is (= {:fx-state {}}
                 (ffx/flashing-fx-snapshot)))
          (level-effects/enqueue-level-effect! :flashing {:mode :state-start}
                                               {:ctx-id "ctx-b" :channel :flashing/fx-test :owner-key [:ctx "ctx-b"]})
          (is (= #{[:ctx "ctx-b"]}
                 (set (keys (:fx-state (ffx/flashing-fx-snapshot))))))))
      (level-effects/call-with-level-effect-runtime runtime-a
        (fn []
          (is (= {:x 1.0 :y 64.0 :z 1.0}
                 (get-in (ffx/flashing-fx-snapshot) [:fx-state [:ctx "ctx-a"] :preview]))))))))

(deftest flashing-fx-snapshot-default-without-registered-state-test
  (is (= {:fx-state {}}
         (ffx/flashing-fx-snapshot))))

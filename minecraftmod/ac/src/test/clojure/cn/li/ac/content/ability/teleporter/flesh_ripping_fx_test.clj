(ns cn.li.ac.content.ability.teleporter.flesh-ripping-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.content.ability.teleporter.flesh-ripping-fx :as frfx]))

(defn- with-fresh-flesh-ripping-fx-runtime [f]
  (level-effects/reset-level-effect-registry-for-test!)
  (frfx/reset-flesh-ripping-fx-for-test!)
      (try
        (f)
        (finally
          (frfx/reset-flesh-ripping-fx-for-test!)
          (level-effects/reset-level-effect-registry-for-test!))))

(use-fixtures :each with-fresh-flesh-ripping-fx-runtime)

(deftest init-registers-flesh-ripping-fx-channels-test
  (let [registered-level* (atom nil)
        registered-topics* (atom #{})]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                         (reset! registered-level* [effect-id effect-map])
                                                         nil)
                  fx-registry/register-fx-channel! (fn [topic _handler]
                                                      (swap! registered-topics* conj topic)
                                                      nil)]
      (frfx/init!)
      (is (= :flesh-ripping (first @registered-level*)))
      (is (= #{:flesh-ripping/fx-start
               :flesh-ripping/fx-update
               :flesh-ripping/fx-perform
               :flesh-ripping/fx-end}
             @registered-topics*)))))

(deftest fx-handler-routes-start-update-perform-end-payloads-test
  (let [handlers* (atom {})
        enqueued* (atom [])]
    (with-redefs [level-effects/register-level-effect! (fn [& _] nil)
                  fx-registry/register-fx-channel! (fn [topic handler]
                                                      (swap! handlers* assoc topic handler)
                                                      nil)
                  level-effects/enqueue-level-effect! (fn [effect-id ctx-id channel payload & opts]
                                                        (swap! enqueued* conj [effect-id ctx-id channel payload opts])
                                                        nil)]
      (frfx/init!)
      ((get @handlers* :flesh-ripping/fx-start) "ctx-1" :flesh-ripping/fx-start nil)
      ((get @handlers* :flesh-ripping/fx-update) "ctx-1" :flesh-ripping/fx-update {:target-x 1.0 :target-y 2.0 :target-z 3.0 :hit? true :target-uuid "target-1"})
      ((get @handlers* :flesh-ripping/fx-perform) "ctx-1" :flesh-ripping/fx-perform {:target-x 4.0 :target-y 5.0 :target-z 6.0 :hit? true :target-uuid "target-2"})
      ((get @handlers* :flesh-ripping/fx-end) "ctx-1" :flesh-ripping/fx-end nil)

      (is (= [[:flesh-ripping {:mode :start
                               :owner-key [:ctx "ctx-1"]
                               :ctx-id "ctx-1"
                               :channel :flesh-ripping/fx-start}
               {:ctx-id "ctx-1"
                :channel :flesh-ripping/fx-start
                :owner-key [:ctx "ctx-1"]}]
              [:flesh-ripping {:mode :update
                               :owner-key [:ctx "ctx-1"]
                               :ctx-id "ctx-1"
                               :channel :flesh-ripping/fx-update
                               :target-x 1.0
                               :target-y 2.0
                               :target-z 3.0
                               :hit? true
                               :target-uuid "target-1"}
               {:ctx-id "ctx-1"
                :channel :flesh-ripping/fx-update
                :owner-key [:ctx "ctx-1"]}]
              [:flesh-ripping {:mode :perform
                               :owner-key [:ctx "ctx-1"]
                               :ctx-id "ctx-1"
                               :channel :flesh-ripping/fx-perform
                               :target-x 4.0
                               :target-y 5.0
                               :target-z 6.0
                               :hit? true
                               :target-uuid "target-2"}
               {:ctx-id "ctx-1"
                :channel :flesh-ripping/fx-perform
                :owner-key [:ctx "ctx-1"]}]
              [:flesh-ripping {:mode :end
                               :owner-key [:ctx "ctx-1"]
                               :ctx-id "ctx-1"
                               :channel :flesh-ripping/fx-end}
               {:ctx-id "ctx-1"
                :channel :flesh-ripping/fx-end
                :owner-key [:ctx "ctx-1"]}]]
             @enqueued*)))))

(deftest enqueue-perform-emits-particles-and-sound-test
  (let [particles* (atom [])
        sounds* (atom [])]
    (with-redefs [client-particles/current-effect-owner (fn [] {:client-session-id "flesh-ripping-test"})
                  client-particles/queue-particle-effect! (fn [& args]
                                                            (swap! particles* conj args)
                                                            nil)
                  client-sounds/queue-sound-effect! (fn [& args]
                                                      (swap! sounds* conj args)
                                                      nil)]
      (frfx/init!)
      (level-effects/enqueue-level-effect! :flesh-ripping "ctx-1" :flesh-ripping/fx-perform {:mode :perform :target-x 1.0 :target-y 2.0 :target-z 3.0 :hit? true :target-uuid "target-1"}
                                         :owner-key [:ctx "ctx-1"])
      (is (= 2 (count @particles*)))
      (is (= 1 (count @sounds*)))
      (is (= "my_mod:tp.flesh_ripping" (:sound-id (second (first @sounds*))))))))

(deftest enqueue-end-clears-state-test
  (with-redefs [client-particles/current-effect-owner (fn [] {:client-session-id "flesh-ripping-test"})]
    (frfx/init!)
    (level-effects/enqueue-level-effect! :flesh-ripping "ctx-1" :flesh-ripping/fx-start {:mode :start}
                                         :owner-key [:ctx "ctx-1"])
    (level-effects/enqueue-level-effect! :flesh-ripping "ctx-1" :flesh-ripping/fx-update {:mode :update :target-x 1.0 :target-y 2.0 :target-z 3.0 :hit? false}
                                         :owner-key [:ctx "ctx-1"])
      (is (some? (get (:fx-state (frfx/flesh-ripping-fx-snapshot)) [:ctx "ctx-1"])))
    (level-effects/enqueue-level-effect! :flesh-ripping "ctx-1" :flesh-ripping/fx-end {:mode :end}
                                         :owner-key [:ctx "ctx-1"])
    (is (nil? (get (:fx-state (frfx/flesh-ripping-fx-snapshot)) [:ctx "ctx-1"])))))



(deftest flesh-ripping-fx-snapshot-default-without-registered-state-test
  (is (= {:fx-state {}}
         (frfx/flesh-ripping-fx-snapshot))))
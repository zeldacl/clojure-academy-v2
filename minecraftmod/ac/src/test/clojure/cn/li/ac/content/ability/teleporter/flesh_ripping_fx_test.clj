(ns cn.li.ac.content.ability.teleporter.flesh-ripping-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.content.ability.teleporter.flesh-ripping-fx :as frfx]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]))

(defn- with-fresh-flesh-ripping-fx-runtime [f]
  (level-effects/reset-level-effect-registry-for-test!)
  (frfx/reset-fx-for-test!)
      (try
        (f)
        (finally
          (frfx/reset-fx-for-test!)
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
                                                        (swap! enqueued* conj (into [effect-id ctx-id channel payload] opts))
                                                        nil)]
      (frfx/init!)
      ((get @handlers* :flesh-ripping/fx-start) "ctx-1" :flesh-ripping/fx-start nil)
      ((get @handlers* :flesh-ripping/fx-update) "ctx-1" :flesh-ripping/fx-update {:target-x 1.0 :target-y 2.0 :target-z 3.0 :hit? true :target-uuid "target-1"})
      ((get @handlers* :flesh-ripping/fx-perform) "ctx-1" :flesh-ripping/fx-perform {:target-x 4.0 :target-y 5.0 :target-z 6.0 :hit? true :target-uuid "target-2"})
      ((get @handlers* :flesh-ripping/fx-end) "ctx-1" :flesh-ripping/fx-end nil)

      (is (= [[:flesh-ripping "ctx-1" :flesh-ripping/fx-start {:mode :start
                                                               :target-x nil :target-y nil :target-z nil
                                                               :hit? nil :target-uuid nil
                                                               :entity-x nil :entity-y nil :entity-z nil
                                                               :target-width nil :target-height nil} :owner-key [:ctx "ctx-1"]]
              [:flesh-ripping "ctx-1" :flesh-ripping/fx-update {:mode :update :target-x 1.0 :target-y 2.0 :target-z 3.0 :hit? true :target-uuid "target-1" :entity-x nil :entity-y nil :entity-z nil :target-width nil :target-height nil} :owner-key [:ctx "ctx-1"]]
              [:flesh-ripping "ctx-1" :flesh-ripping/fx-perform {:mode :perform :target-x 4.0 :target-y 5.0 :target-z 6.0 :hit? true :target-uuid "target-2" :entity-x nil :entity-y nil :entity-z nil :target-width nil :target-height nil} :owner-key [:ctx "ctx-1"]]
              [:flesh-ripping "ctx-1" :flesh-ripping/fx-end {:mode :end} :owner-key [:ctx "ctx-1"]]]
             @enqueued*)))))

(deftest enqueue-perform-emits-particles-and-sound-test
  (let [particles* (atom [])
        sounds* (atom [])]
    (with-redefs [client-bridge/run-client-effect! (fn [& _] nil)
                  client-particles/current-effect-owner (fn [] {:client-session-id "flesh-ripping-test"})
                  client-particles/queue-particle-effect! (fn [& args]
                                                            (swap! particles* conj args)
                                                            nil)
                  client-sounds/queue-sound-effect! (fn [& args]
                                                      (swap! sounds* conj args)
                                                      nil)]
      (frfx/init!)
      (level-effects/enqueue-level-effect! :flesh-ripping "ctx-1" :flesh-ripping/fx-perform {:mode :perform :target-x 1.0 :target-y 2.0 :target-z 3.0 :hit? true :target-uuid "target-1"}
                                         :owner-key [:ctx "ctx-1"])
      (is (zero? (count @particles*)))
      (is (= 1 (count @sounds*)))
      (is (= "academy:tp.guts" (:sound-id (second (first @sounds*))))))))

(deftest build-plan-emits-scaled-marker-cube-test
  (with-redefs [client-particles/current-effect-owner (fn [] {:client-session-id "flesh-ripping-test"})]
    (frfx/init!)
    ;; No target: disabled color, 1.0x1.0 box.
    (level-effects/enqueue-level-effect! :flesh-ripping "ctx-1" :flesh-ripping/fx-update
                                         {:mode :update :target-x 1.0 :target-y 2.0 :target-z 3.0 :hit? false}
                                         :owner-key [:ctx "ctx-1"])
    (let [{:keys [ops]} (cn.li.ac.ability.client.fx-templates.arc-beam/effect-build-plan
                         :flesh-ripping nil {:player-uuid "viewer"} 0 nil)]
      (is (= 12 (count ops)))
      (is (= {:r 74 :g 74 :b 74 :a 160} (:color (first ops)))))
    ;; Target: threatening color, box scaled to width*1.2 / height*1.2.
    (level-effects/enqueue-level-effect! :flesh-ripping "ctx-1" :flesh-ripping/fx-update
                                         {:mode :update :target-x 1.0 :target-y 2.0 :target-z 3.0
                                          :hit? true :target-uuid "t"
                                          :target-width 1.0 :target-height 2.0}
                                         :owner-key [:ctx "ctx-1"])
    (let [{:keys [ops]} (cn.li.ac.ability.client.fx-templates.arc-beam/effect-build-plan
                         :flesh-ripping nil {:player-uuid "viewer"} 0 nil)]
      (is (= 12 (count ops)))
      (is (= {:r 185 :g 25 :b 25 :a 180} (:color (first ops))))
      ;; Half-extents 0.6 / 1.2 -> edge endpoints stay within half a block of center.
      (let [^cn.li.mcmod.math.V3 p1 (:p1 (first ops))]
        (is (< (Math/abs (- (.y p1) 2.0)) 1.3))))))

(deftest perform-clears-marker-state-test
  (with-redefs [client-bridge/run-client-effect! (fn [& _] nil)
                client-particles/current-effect-owner (fn [] {:client-session-id "flesh-ripping-test"})]
    (frfx/init!)
    (level-effects/enqueue-level-effect! :flesh-ripping "ctx-1" :flesh-ripping/fx-start {:mode :start}
                                         :owner-key [:ctx "ctx-1"])
    (is (some? (get (:fx-state (frfx/fx-snapshot)) [:ctx "ctx-1"])))
    (level-effects/enqueue-level-effect! :flesh-ripping "ctx-1" :flesh-ripping/fx-perform {:mode :perform :hit? false}
                                         :owner-key [:ctx "ctx-1"])
    ;; Upstream c_endEffect marker.setDead on MSG_EFFECT_END — even on a miss.
    (is (nil? (get (:fx-state (frfx/fx-snapshot)) [:ctx "ctx-1"])))))

(deftest enqueue-end-clears-state-test
  (with-redefs [client-bridge/run-client-effect! (fn [& _] nil)
                client-particles/current-effect-owner (fn [] {:client-session-id "flesh-ripping-test"})]
    (frfx/init!)
    (level-effects/enqueue-level-effect! :flesh-ripping "ctx-1" :flesh-ripping/fx-start {:mode :start}
                                         :owner-key [:ctx "ctx-1"])
    (level-effects/enqueue-level-effect! :flesh-ripping "ctx-1" :flesh-ripping/fx-update {:mode :update :target-x 1.0 :target-y 2.0 :target-z 3.0 :hit? false}
                                         :owner-key [:ctx "ctx-1"])
      (is (some? (get (:fx-state (frfx/fx-snapshot)) [:ctx "ctx-1"])))
    (level-effects/enqueue-level-effect! :flesh-ripping "ctx-1" :flesh-ripping/fx-end {:mode :end}
                                         :owner-key [:ctx "ctx-1"])
    (is (nil? (get (:fx-state (frfx/fx-snapshot)) [:ctx "ctx-1"])))))



(deftest fx-snapshot-default-without-registered-state-test
  (is (= {:fx-state {}}
         (frfx/fx-snapshot))))
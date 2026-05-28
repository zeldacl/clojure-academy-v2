(ns cn.li.ac.content.ability.teleporter.teleporter-fx-owner-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.content.ability.teleporter.flashing-fx :as flashing-fx]
            [cn.li.ac.content.ability.teleporter.flesh-ripping-fx :as flesh-ripping-fx]
            [cn.li.ac.content.ability.teleporter.mark-teleport-fx :as mark-teleport-fx]
            [cn.li.ac.content.ability.teleporter.penetrate-teleport-fx :as penetrate-teleport-fx]
            [cn.li.ac.content.ability.teleporter.shift-teleport-fx :as shift-teleport-fx]))

(defn- reset-fixture [f]
  (flashing-fx/call-with-flashing-fx-runtime
    (flashing-fx/create-flashing-fx-runtime)
    (fn []
      (flesh-ripping-fx/call-with-flesh-ripping-fx-runtime
        (flesh-ripping-fx/create-flesh-ripping-fx-runtime)
        (fn []
          (mark-teleport-fx/call-with-mark-teleport-fx-runtime
            (mark-teleport-fx/create-mark-teleport-fx-runtime)
            (fn []
              (penetrate-teleport-fx/call-with-penetrate-teleport-fx-runtime
                (penetrate-teleport-fx/create-penetrate-teleport-fx-runtime)
                (fn []
                  (shift-teleport-fx/call-with-shift-teleport-fx-runtime
                    (shift-teleport-fx/create-shift-teleport-fx-runtime)
                    (fn []
                      (flashing-fx/reset-flashing-fx-for-test!)
                      (flesh-ripping-fx/reset-flesh-ripping-fx-for-test!)
                      (mark-teleport-fx/reset-mark-teleport-fx-for-test!)
                      (penetrate-teleport-fx/reset-penetrate-teleport-fx-for-test!)
                      (shift-teleport-fx/reset-shift-teleport-fx-for-test!)
                      (f)
                      (flashing-fx/reset-flashing-fx-for-test!)
                      (flesh-ripping-fx/reset-flesh-ripping-fx-for-test!)
                      (mark-teleport-fx/reset-mark-teleport-fx-for-test!)
                      (penetrate-teleport-fx/reset-penetrate-teleport-fx-for-test!)
                      (shift-teleport-fx/reset-shift-teleport-fx-for-test!))))))))))))

(use-fixtures :each reset-fixture)

(defn- event [ctx-id channel payload]
  {:payload payload
   :ctx-id ctx-id
   :channel channel
   :owner-key [:ctx ctx-id]})

(deftest teleporter-stateful-fx-keep-state-per-owner-test
  (let [flashing-enqueue! (var-get #'cn.li.ac.content.ability.teleporter.flashing-fx/enqueue!)
        flesh-enqueue! (var-get #'cn.li.ac.content.ability.teleporter.flesh-ripping-fx/enqueue!)
        mark-enqueue! (var-get #'cn.li.ac.content.ability.teleporter.mark-teleport-fx/enqueue!)
        penetrate-enqueue! (var-get #'cn.li.ac.content.ability.teleporter.penetrate-teleport-fx/enqueue!)
        shift-enqueue! (var-get #'cn.li.ac.content.ability.teleporter.shift-teleport-fx/enqueue!)]
    (with-redefs [client-particles/queue-particle-effect! (fn [_] nil)
                  client-particles/current-effect-owner (fn [] {:client-session-id "teleporter-owner-test"})
                  client-sounds/queue-sound-effect! (fn [& _] nil)
                  client-particles/queue-particle-effect! (fn [& _] nil)]
      (flashing-enqueue! (event "ctx-a" :flashing/fx-state-start {:mode :state-start}))
      (flashing-enqueue! (event "ctx-b" :flashing/fx-state-start {:mode :state-start}))
      (flashing-enqueue! (event "ctx-a" :flashing/fx-preview-update
                                {:mode :preview-update :to-x 1.0 :to-y 64.0 :to-z 1.0}))
      (flashing-enqueue! (event "ctx-b" :flashing/fx-preview-update
                                {:mode :preview-update :to-x 2.0 :to-y 64.0 :to-z 2.0}))

      (flesh-enqueue! (event "ctx-a" :flesh-ripping/fx-start {:mode :start}))
      (flesh-enqueue! (event "ctx-b" :flesh-ripping/fx-start {:mode :start}))
      (flesh-enqueue! (event "ctx-a" :flesh-ripping/fx-update
                             {:mode :update :target-x 1.0 :target-y 64.0 :target-z 1.0 :hit? false}))
      (flesh-enqueue! (event "ctx-b" :flesh-ripping/fx-update
                             {:mode :update :target-x 2.0 :target-y 64.0 :target-z 2.0 :hit? true}))

      (mark-enqueue! (event "ctx-a" :mark-teleport/fx-start {:mode :start}))
      (mark-enqueue! (event "ctx-b" :mark-teleport/fx-start {:mode :start}))
      (mark-enqueue! (event "ctx-a" :mark-teleport/fx-update
                            {:mode :update :target {:x 1.0 :y 64.0 :z 1.0} :distance 3.0}))
      (mark-enqueue! (event "ctx-b" :mark-teleport/fx-update
                            {:mode :update :target {:x 2.0 :y 64.0 :z 2.0} :distance 9.0}))

      (penetrate-enqueue! (event "ctx-a" :penetrate-tp/fx-start {:mode :start}))
      (penetrate-enqueue! (event "ctx-b" :penetrate-tp/fx-start {:mode :start}))
      (penetrate-enqueue! (event "ctx-a" :penetrate-tp/fx-update
                                 {:mode :update :x 1.0 :y 64.0 :z 1.0 :available? false :distance 5.0}))
      (penetrate-enqueue! (event "ctx-b" :penetrate-tp/fx-update
                                 {:mode :update :x 2.0 :y 64.0 :z 2.0 :available? true :distance 7.0}))

      (shift-enqueue! (event "ctx-a" :shift-tp/fx-start {:mode :start}))
      (shift-enqueue! (event "ctx-b" :shift-tp/fx-start {:mode :start}))
      (shift-enqueue! (event "ctx-a" :shift-tp/fx-update
                             {:mode :update :x 1.0 :y 64.0 :z 1.0 :target-count 0 :target-hit? false :hand-valid? true}))
      (shift-enqueue! (event "ctx-b" :shift-tp/fx-update
                             {:mode :update :x 2.0 :y 64.0 :z 2.0 :target-count 2 :target-hit? true :hand-valid? true}))

      (is (= 1.0 (get-in (flashing-fx/flashing-fx-snapshot) [:fx-state [:ctx "ctx-a"] :preview :x])))
      (is (= 2.0 (get-in (flashing-fx/flashing-fx-snapshot) [:fx-state [:ctx "ctx-b"] :preview :x])))
      (is (false? (get-in (flesh-ripping-fx/flesh-ripping-fx-snapshot) [:fx-state [:ctx "ctx-a"] :hit?])))
      (is (true? (get-in (flesh-ripping-fx/flesh-ripping-fx-snapshot) [:fx-state [:ctx "ctx-b"] :hit?])))
      (is (= 3.0 (get-in (mark-teleport-fx/mark-teleport-fx-snapshot) [:effect-state [:ctx "ctx-a"] :distance])))
      (is (= 9.0 (get-in (mark-teleport-fx/mark-teleport-fx-snapshot) [:effect-state [:ctx "ctx-b"] :distance])))
      (is (false? (get-in (penetrate-teleport-fx/penetrate-teleport-fx-snapshot) [:fx-state [:ctx "ctx-a"] :available?])))
      (is (true? (get-in (penetrate-teleport-fx/penetrate-teleport-fx-snapshot) [:fx-state [:ctx "ctx-b"] :available?])))
      (is (= 0 (get-in (shift-teleport-fx/shift-teleport-fx-snapshot) [:fx-state [:ctx "ctx-a"] :target-count])))
      (is (= 2 (get-in (shift-teleport-fx/shift-teleport-fx-snapshot) [:fx-state [:ctx "ctx-b"] :target-count])))

      (flashing-fx/clear-flashing-owner! [:ctx "ctx-a"])
      (flesh-ripping-fx/clear-flesh-ripping-owner! [:ctx "ctx-a"])
      (mark-teleport-fx/clear-mark-teleport-owner! [:ctx "ctx-a"])
      (penetrate-teleport-fx/clear-penetrate-teleport-owner! [:ctx "ctx-a"])
      (shift-teleport-fx/clear-shift-teleport-owner! [:ctx "ctx-a"])

      (is (nil? (get-in (flashing-fx/flashing-fx-snapshot) [:fx-state [:ctx "ctx-a"]])))
      (is (some? (get-in (flashing-fx/flashing-fx-snapshot) [:fx-state [:ctx "ctx-b"]])))
      (is (nil? (get-in (flesh-ripping-fx/flesh-ripping-fx-snapshot) [:fx-state [:ctx "ctx-a"]])))
      (is (some? (get-in (flesh-ripping-fx/flesh-ripping-fx-snapshot) [:fx-state [:ctx "ctx-b"]])))
      (is (nil? (get-in (mark-teleport-fx/mark-teleport-fx-snapshot) [:effect-state [:ctx "ctx-a"]])))
      (is (some? (get-in (mark-teleport-fx/mark-teleport-fx-snapshot) [:effect-state [:ctx "ctx-b"]])))
      (is (nil? (get-in (penetrate-teleport-fx/penetrate-teleport-fx-snapshot) [:fx-state [:ctx "ctx-a"]])))
      (is (some? (get-in (penetrate-teleport-fx/penetrate-teleport-fx-snapshot) [:fx-state [:ctx "ctx-b"]])))
      (is (nil? (get-in (shift-teleport-fx/shift-teleport-fx-snapshot) [:fx-state [:ctx "ctx-a"]])))
      (is (some? (get-in (shift-teleport-fx/shift-teleport-fx-snapshot) [:fx-state [:ctx "ctx-b"]]))))))

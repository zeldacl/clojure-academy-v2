(ns cn.li.ac.content.ability.electromaster.electromaster-fx-owner-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.hand-effects :as hand-effects]
            [cn.li.ac.content.ability.electromaster.current-charging-fx :as current-charging-fx]
            [cn.li.ac.content.ability.electromaster.mag-manip-fx :as mag-manip-fx]))

(defn- reset-fixture [f]
  (try
        (hand-effects/reset-hand-effect-registry-for-test!)
        (mag-manip-fx/reset-mag-manip-fx-for-test!)
        (current-charging-fx/reset-current-charging-fx-for-test!)
        (f)
        (finally
          (mag-manip-fx/reset-mag-manip-fx-for-test!)
          (current-charging-fx/reset-current-charging-fx-for-test!)
          (hand-effects/reset-hand-effect-registry-for-test!))))

(use-fixtures :each reset-fixture)

(deftest electromaster-fx-keep-state-per-owner-test
  (let [mag-enqueue-state! (var-get #'cn.li.ac.content.ability.electromaster.mag-manip-fx/enqueue-state!)
        charging-enqueue-state! (var-get #'cn.li.ac.content.ability.electromaster.current-charging-fx/enqueue-state!)]
    (with-redefs [client-sounds/current-effect-owner (fn [] {:client-session-id "electromaster-owner-test"})
                  client-sounds/queue-current-sound-effect! (fn [& _] nil)
                  client-sounds/queue-sound-effect! (fn [& _] nil)]
      (hand-effects/update-effect-state! :mag-manip mag-enqueue-state!
        {:owner-key [:ctx "ctx-a"] :ctx-id "ctx-a" :mode :hold-start :block-id "minecraft:iron_block"})
      (hand-effects/update-effect-state! :mag-manip mag-enqueue-state!
        {:owner-key [:ctx "ctx-b"] :ctx-id "ctx-b" :mode :hold-start :block-id "minecraft:gold_block"})
      (hand-effects/update-effect-state! :mag-manip mag-enqueue-state!
        {:owner-key [:ctx "ctx-a"] :ctx-id "ctx-a" :mode :hold-loop :block-id "minecraft:copper_block"})

      (hand-effects/update-effect-state! :current-charging charging-enqueue-state!
        {:ctx-id "ctx-a" :mode :start :is-item false})
      (hand-effects/update-effect-state! :current-charging charging-enqueue-state!
        {:ctx-id "ctx-b" :mode :start :is-item true})
      (hand-effects/update-effect-state! :current-charging charging-enqueue-state!
        {:ctx-id "ctx-a" :mode :update :charge-ticks 12 :good? true})
      (hand-effects/update-effect-state! :current-charging charging-enqueue-state!
        {:ctx-id "ctx-b" :mode :update :charge-ticks 30 :good? false})

      (let [mag-snapshot (mag-manip-fx/mag-manip-fx-snapshot)
            charging-snapshot (current-charging-fx/current-charging-fx-snapshot)]
        (is (= "minecraft:copper_block" (get-in mag-snapshot [:states [:ctx "ctx-a"] :block-id])))
        (is (= "minecraft:gold_block" (get-in mag-snapshot [:states [:ctx "ctx-b"] :block-id])))
        (is (= 12 (get-in charging-snapshot [:states [:ctx "ctx-a"] :charge-ticks])))
        (is (= 30 (get-in charging-snapshot [:states [:ctx "ctx-b"] :charge-ticks]))))

      (mag-manip-fx/clear-mag-manip-owner! [:ctx "ctx-a"])
      (current-charging-fx/clear-current-charging-owner! [:ctx "ctx-a"])

      (let [mag-snapshot (mag-manip-fx/mag-manip-fx-snapshot)
            charging-snapshot (current-charging-fx/current-charging-fx-snapshot)]
        (is (nil? (get-in mag-snapshot [:states [:ctx "ctx-a"]])))
        (is (some? (get-in mag-snapshot [:states [:ctx "ctx-b"]])))
        (is (nil? (get-in charging-snapshot [:states [:ctx "ctx-a"]])))
        (is (some? (get-in charging-snapshot [:states [:ctx "ctx-b"]])))))))



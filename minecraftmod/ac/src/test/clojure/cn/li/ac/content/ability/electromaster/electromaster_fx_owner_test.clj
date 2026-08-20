(ns cn.li.ac.content.ability.electromaster.electromaster-fx-owner-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.client.effect-controller :as vfx-hand]
            [cn.li.ac.content.ability.electromaster.mag-manip-fx :as mag-manip-fx]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]))

(defn- invoke-mag-enqueue! [ctx-id channel payload]
  (arc-beam/enqueue-for-test! :mag-manip ctx-id channel payload {:runtime :hand}))

(defn- reset-fixture [f]
  (try
    (vfx-hand/reset-hand-effect-registry-for-test!)
    (mag-manip-fx/reset-fx-for-test!)
    (mag-manip-fx/init!)
    (f)
    (finally
      (mag-manip-fx/reset-fx-for-test!)
      (vfx-hand/reset-hand-effect-registry-for-test!))))

(use-fixtures :each reset-fixture)

(deftest electromaster-fx-keep-state-per-owner-test
  (with-redefs [client-sounds/current-effect-owner (fn [] {:client-session-id "electromaster-owner-test"})
                client-sounds/queue-current-sound-effect! (fn [& _] nil)
                client-sounds/queue-sound-effect! (fn [& _] nil)
                client-bridge/game-time-ms (constantly 1000)
                client-bridge/run-client-effect! (fn [& _] nil)]
    (invoke-mag-enqueue! "ctx-a" :mag-manip/fx-hold {:mode :hold-start :block-id "minecraft:iron_block"})
    (invoke-mag-enqueue! "ctx-b" :mag-manip/fx-hold {:mode :hold-start :block-id "minecraft:gold_block"})
    (invoke-mag-enqueue! "ctx-a" :mag-manip/fx-hold {:mode :hold-loop :block-id "minecraft:copper_block"})
    (let [snapshot (mag-manip-fx/fx-snapshot)]
      (is (= "minecraft:copper_block" (get-in snapshot [:states [:ctx "ctx-a"] :block-id])))
      (is (= "minecraft:gold_block" (get-in snapshot [:states [:ctx "ctx-b"] :block-id]))))
    (mag-manip-fx/clear-fx-owner! [:ctx "ctx-a"])
    (let [snapshot (mag-manip-fx/fx-snapshot)]
      (is (nil? (get-in snapshot [:states [:ctx "ctx-a"]])))
      (is (some? (get-in snapshot [:states [:ctx "ctx-b"]]))))))

(ns cn.li.ac.content.ability.vecmanip.vec-reflection-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.content.ability.vecmanip.vec-reflection-fx :as vrfx]))

(defn- reset-fixture [f]
  (level-effects/call-with-level-effect-runtime
    (level-effects/create-level-effect-runtime)
    (fn []
      (try
        (level-effects/reset-level-effect-registry-for-test!)
        (vrfx/reset-vec-reflection-fx-for-test!)
        (f)
        (finally
          (vrfx/reset-vec-reflection-fx-for-test!)
          (level-effects/reset-level-effect-registry-for-test!))))))

(use-fixtures :each reset-fixture)

(defn- event
  [ctx-id payload]
  {:payload payload
   :ctx-id ctx-id
   :channel :vec-reflection/fx-reflect-entity
   :owner-key [:ctx ctx-id]})

(deftest enqueue-reflect-entity-requires-reflected-flag-test
  (let [enqueue-state! (var-get #'cn.li.ac.content.ability.vecmanip.vec-reflection-fx/enqueue-state!)]
    (is (= (vrfx/default-vec-reflection-fx-runtime-state)
           (vrfx/vec-reflection-fx-snapshot)))
    (level-effects/update-effect-state! :vec-reflection
      enqueue-state!
      (event "ctx-main" {:mode :reflect-entity :x 1.0 :y 2.0 :z 3.0 :reflected? false}))
    (is (empty? (:wave-effects (vrfx/vec-reflection-fx-snapshot))))
    (level-effects/update-effect-state! :vec-reflection
      enqueue-state!
      (event "ctx-main" {:mode :reflect-entity :x 1.0 :y 2.0 :z 3.0 :reflected? true}))
    (let [waves (get (:wave-effects (vrfx/vec-reflection-fx-snapshot)) [:ctx "ctx-main"])]
      (is (= 1 (count waves)))
      (is (= 3.0 (:z (first waves)))))))

(deftest two-owners-keep-vec-reflection-state-and-waves-independent-test
  (let [enqueue-state! (var-get #'cn.li.ac.content.ability.vecmanip.vec-reflection-fx/enqueue-state!)]
    (level-effects/update-effect-state! :vec-reflection
      enqueue-state!
      (event "ctx-a" {:mode :start :source-player-id "player-a"}))
    (level-effects/update-effect-state! :vec-reflection
      enqueue-state!
      (event "ctx-b" {:mode :start :source-player-id "player-b"}))
    (level-effects/update-effect-state! :vec-reflection
      enqueue-state!
      (event "ctx-a" {:mode :reflect-entity :x 1.0 :y 2.0 :z 3.0 :reflected? true :source-player-id "player-a"}))
    (level-effects/update-effect-state! :vec-reflection
      enqueue-state!
      (event "ctx-b" {:mode :reflect-entity :x 4.0 :y 5.0 :z 6.0 :reflected? true :source-player-id "player-b"}))
    (let [snapshot (vrfx/vec-reflection-fx-snapshot)]
      (is (= #{[:ctx "ctx-a"] [:ctx "ctx-b"]}
             (set (keys (:effect-state snapshot)))))
      (is (= 1 (count (get (:wave-effects snapshot) [:ctx "ctx-a"]))))
      (is (= 1 (count (get (:wave-effects snapshot) [:ctx "ctx-b"]))))
      (vrfx/clear-vec-reflection-owner! [:ctx "ctx-a"])
      (let [after-clear (vrfx/vec-reflection-fx-snapshot)]
        (is (nil? (get (:effect-state after-clear) [:ctx "ctx-a"])))
        (is (= 1 (count (get (:wave-effects after-clear) [:ctx "ctx-b"]))))))))

(deftest vec-reflection-fx-runtime-isolation-test
  (let [runtime-a (level-effects/create-level-effect-runtime)
        runtime-b (level-effects/create-level-effect-runtime)
        enqueue-state! (var-get #'cn.li.ac.content.ability.vecmanip.vec-reflection-fx/enqueue-state!)]
    (level-effects/call-with-level-effect-runtime
      runtime-a
      (fn []
        (level-effects/update-effect-state! :vec-reflection
          enqueue-state!
          (event "ctx-a" {:mode :start :source-player-id "player-a"}))
        (level-effects/update-effect-state! :vec-reflection
          enqueue-state!
          (event "ctx-a" {:mode :reflect-entity :x 1.0 :y 2.0 :z 3.0 :reflected? true :source-player-id "player-a"}))
        (is (= 1 (count (get (:wave-effects (vrfx/vec-reflection-fx-snapshot)) [:ctx "ctx-a"]))))))
    (level-effects/call-with-level-effect-runtime
      runtime-b
      (fn []
        (is (= (vrfx/default-vec-reflection-fx-runtime-state)
               (vrfx/vec-reflection-fx-snapshot)))
        (level-effects/update-effect-state! :vec-reflection
          enqueue-state!
          (event "ctx-b" {:mode :start :source-player-id "player-b"}))
        (is (= #{[:ctx "ctx-b"]}
               (set (keys (:effect-state (vrfx/vec-reflection-fx-snapshot))))))))
    (level-effects/call-with-level-effect-runtime
      runtime-a
      (fn []
        (is (= 1 (count (get (:wave-effects (vrfx/vec-reflection-fx-snapshot)) [:ctx "ctx-a"]))))))))

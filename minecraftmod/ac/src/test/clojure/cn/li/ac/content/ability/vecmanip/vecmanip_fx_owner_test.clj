(ns cn.li.ac.content.ability.vecmanip.vecmanip-fx-owner-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.content.ability.vecmanip.plasma-cannon-fx :as plasma-cannon-fx]
            [cn.li.ac.content.ability.vecmanip.storm-wing-fx :as storm-wing-fx]
            [cn.li.ac.content.ability.vecmanip.vec-accel-fx :as vec-accel-fx]
            [cn.li.ac.content.ability.vecmanip.vec-deviation-fx :as vec-deviation-fx]))

(defn- reset-fixture [f]
  (vec-deviation-fx/call-with-vec-deviation-fx-runtime
    (vec-deviation-fx/create-vec-deviation-fx-runtime)
    (fn []
      (vec-accel-fx/call-with-vec-accel-fx-runtime
        (vec-accel-fx/create-vec-accel-fx-runtime)
        (fn []
          (storm-wing-fx/call-with-storm-wing-fx-runtime
            (storm-wing-fx/create-storm-wing-fx-runtime)
            (fn []
              (plasma-cannon-fx/call-with-plasma-cannon-fx-runtime
                (plasma-cannon-fx/create-plasma-cannon-fx-runtime)
                (fn []
                  (vec-deviation-fx/reset-vec-deviation-fx-for-test!)
                  (vec-accel-fx/reset-vec-accel-fx-for-test!)
                  (storm-wing-fx/reset-storm-wing-fx-for-test!)
                  (plasma-cannon-fx/reset-plasma-cannon-fx-for-test!)
                  (f)
                  (vec-deviation-fx/reset-vec-deviation-fx-for-test!)
                  (vec-accel-fx/reset-vec-accel-fx-for-test!)
                  (storm-wing-fx/reset-storm-wing-fx-for-test!)
                  (plasma-cannon-fx/reset-plasma-cannon-fx-for-test!))))))))))

(use-fixtures :each reset-fixture)

(defn- event [ctx-id channel payload]
  {:payload payload
   :ctx-id ctx-id
   :channel channel
   :owner-key [:ctx ctx-id]})

(deftest vec-deviation-keeps-state-and-waves-per-owner-test
  (let [enqueue! (var-get #'cn.li.ac.content.ability.vecmanip.vec-deviation-fx/enqueue!)]
    (enqueue! (event "ctx-a" :vec-deviation/fx-start {:mode :start}))
    (enqueue! (event "ctx-b" :vec-deviation/fx-start {:mode :start}))
    (enqueue! (event "ctx-a" :vec-deviation/fx-stop-entity
                     {:mode :stop-entity :x 1.0 :y 64.0 :z 1.0 :marked? true}))
    (enqueue! (event "ctx-b" :vec-deviation/fx-stop-entity
                     {:mode :stop-entity :x 2.0 :y 64.0 :z 2.0 :marked? true}))
    (let [snapshot (vec-deviation-fx/vec-deviation-fx-snapshot)]
      (is (:active? (get (:effect-state snapshot) [:ctx "ctx-a"])))
      (is (:active? (get (:effect-state snapshot) [:ctx "ctx-b"])))
      (is (= 1 (count (get (:wave-effects snapshot) [:ctx "ctx-a"]))))
      (is (= 1 (count (get (:wave-effects snapshot) [:ctx "ctx-b"]))))
      (vec-deviation-fx/clear-vec-deviation-owner! [:ctx "ctx-a"])
      (let [after-clear (vec-deviation-fx/vec-deviation-fx-snapshot)]
        (is (nil? (get (:effect-state after-clear) [:ctx "ctx-a"])))
        (is (nil? (get (:wave-effects after-clear) [:ctx "ctx-a"])))
        (is (:active? (get (:effect-state after-clear) [:ctx "ctx-b"])))))))

(deftest vec-accel-keeps-preview-state-per-owner-test
  (let [enqueue! (var-get #'cn.li.ac.content.ability.vecmanip.vec-accel-fx/enqueue!)]
    (enqueue! (event "ctx-a" :vec-accel/fx-start {:mode :start}))
    (enqueue! (event "ctx-b" :vec-accel/fx-start {:mode :start}))
    (enqueue! (event "ctx-a" :vec-accel/fx-update
                     {:mode :update :charge-ticks 12 :can-perform? true
                      :look-dir {:x 1.0 :y 0.0 :z 0.0}
                      :init-vel {:x 1.0 :y 0.5 :z 0.0}}))
    (enqueue! (event "ctx-b" :vec-accel/fx-update
                     {:mode :update :charge-ticks 3 :can-perform? false
                      :look-dir {:x 0.0 :y 0.0 :z 1.0}
                      :init-vel {:x 0.0 :y 0.5 :z 1.0}}))
    (let [snapshot (vec-accel-fx/vec-accel-fx-snapshot)]
      (is (= 12 (:charge-ticks (get (:effect-state snapshot) [:ctx "ctx-a"]))))
      (is (= 3 (:charge-ticks (get (:effect-state snapshot) [:ctx "ctx-b"]))))
      (vec-accel-fx/clear-vec-accel-owner! [:ctx "ctx-a"])
      (let [after-clear (vec-accel-fx/vec-accel-fx-snapshot)]
        (is (nil? (get (:effect-state after-clear) [:ctx "ctx-a"])))
        (is (some? (get (:effect-state after-clear) [:ctx "ctx-b"])))))))

(deftest storm-wing-keeps-state-per-owner-test
  (let [enqueue! (var-get #'cn.li.ac.content.ability.vecmanip.storm-wing-fx/enqueue!)]
    (with-redefs [client-particles/current-effect-owner (fn [] {:client-session-id "storm-wing-owner"})
                  client-sounds/queue-sound-effect! (fn [& _] nil)]
      (enqueue! (event "ctx-a" :storm-wing/fx-start {:mode :start :charge-ticks 70}))
      (enqueue! (event "ctx-b" :storm-wing/fx-start {:mode :start :charge-ticks 40}))
      (enqueue! (event "ctx-a" :storm-wing/fx-update
                       {:mode :update :phase :charging :charge-ticks 35 :charge-ratio 0.5}))
      (enqueue! (event "ctx-b" :storm-wing/fx-update
                       {:mode :update :phase :flying :charge-ticks 40 :charge-ratio 1.0}))
      (let [snapshot (storm-wing-fx/storm-wing-fx-snapshot)]
        (is (= :charging (:phase (get (:effect-state snapshot) [:ctx "ctx-a"]))))
        (is (= :flying (:phase (get (:effect-state snapshot) [:ctx "ctx-b"]))))
        (storm-wing-fx/clear-storm-wing-owner! [:ctx "ctx-a"])
        (let [after-clear (storm-wing-fx/storm-wing-fx-snapshot)]
          (is (nil? (get (:effect-state after-clear) [:ctx "ctx-a"])))
          (is (some? (get (:effect-state after-clear) [:ctx "ctx-b"]))))))))

(deftest plasma-cannon-keeps-charge-state-per-owner-test
  (let [enqueue! (var-get #'cn.li.ac.content.ability.vecmanip.plasma-cannon-fx/enqueue!)
        build-plan (var-get #'cn.li.ac.content.ability.vecmanip.plasma-cannon-fx/build-plan)]
    (with-redefs [client-particles/current-effect-owner (fn [] {:client-session-id "plasma-cannon-owner"})
                  client-sounds/queue-sound-effect! (fn [& _] nil)
                  client-particles/queue-particle-effect! (fn [& _] nil)]
      (enqueue! (event "ctx-a" :plasma-cannon/fx-start
                       {:mode :start :charge-pos {:x 1.0 :y 64.0 :z 1.0}}))
      (enqueue! (event "ctx-b" :plasma-cannon/fx-start
                       {:mode :start :charge-pos {:x 2.0 :y 64.0 :z 2.0}}))
      (enqueue! (event "ctx-a" :plasma-cannon/fx-update
                       {:mode :update :charge-ticks 24 :charge-pos {:x 1.0 :y 64.0 :z 1.0}
                        :flight-ticks 0 :state :charging}))
      (enqueue! (event "ctx-b" :plasma-cannon/fx-update
                       {:mode :update :charge-ticks 8 :charge-pos {:x 2.0 :y 64.0 :z 2.0}
                        :flight-ticks 2 :state :go}))
      (let [snapshot (plasma-cannon-fx/plasma-cannon-fx-snapshot)
            plan (build-plan nil nil 0)]
        (is (= 24 (:charge-ticks (get (:effect-state snapshot) [:ctx "ctx-a"]))))
        (is (= :go (:state (get (:effect-state snapshot) [:ctx "ctx-b"]))))
        (is (= 2 (count (:ops plan))))
        (plasma-cannon-fx/clear-plasma-cannon-owner! [:ctx "ctx-a"])
        (let [after-clear (plasma-cannon-fx/plasma-cannon-fx-snapshot)]
          (is (nil? (get (:effect-state after-clear) [:ctx "ctx-a"])))
          (is (some? (get (:effect-state after-clear) [:ctx "ctx-b"]))))))))

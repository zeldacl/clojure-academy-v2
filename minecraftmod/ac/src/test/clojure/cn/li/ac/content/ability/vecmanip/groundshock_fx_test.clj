(ns cn.li.ac.content.ability.vecmanip.groundshock-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.content.ability.vecmanip.groundshock-fx :as gfx]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.hand-effects :as hand-effects]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(defn- reset-fixture [f]
  (binding [runtime-hooks/*client-session-id* :test-session]
    (hand-effects/call-with-hand-effect-runtime
      (hand-effects/create-hand-effect-runtime)
      (fn []
        (hand-effects/reset-hand-effect-registry-for-test!)
        (gfx/reset-groundshock-fx-for-test!)
        (try
          (f)
          (finally
            (hand-effects/reset-hand-effect-registry-for-test!)
            (gfx/reset-groundshock-fx-for-test!)))))))

(use-fixtures :each reset-fixture)

(deftest fx-handler-routes-start-update-perform-end-payloads-test
  (let [handler* (atom nil)
        hand-enqueued* (atom [])
        level-enqueued* (atom [])]
    (with-redefs [level-effects/register-level-effect! (fn [& _] nil)
                  hand-effects/register-hand-effect! (fn [& _] nil)
                  fx-registry/register-fx-channels! (fn [_ handler]
                                                      (reset! handler* handler)
                                                      nil)
                  hand-effects/enqueue-hand-effect! (fn [effect-id payload]
                                                      (swap! hand-enqueued* conj [effect-id payload])
                                                      nil)
                  level-effects/enqueue-level-effect! (fn [effect-id payload fx-context]
                                                        (swap! level-enqueued* conj [effect-id payload fx-context])
                                                        nil)]
      (gfx/init!)
      (@handler* "ctx-1" :groundshock/fx-start nil)
      (@handler* "ctx-1" :groundshock/fx-update {:charge-ticks 7})
      (@handler* "ctx-1" :groundshock/fx-perform {:affected-blocks [{:x 1 :y 2 :z 3 :block-id "minecraft:stone"}]
                                                   :broken-blocks [{:x 1 :y 2 :z 3}]})
      (@handler* "ctx-1" :groundshock/fx-end {:performed? false})

      (is (= [[:groundshock {:owner-key [:ctx "ctx-1"]
                             :ctx-id "ctx-1"
                             :channel :groundshock/fx-start
                             :mode :start}]
              [:groundshock {:owner-key [:ctx "ctx-1"]
                             :ctx-id "ctx-1"
                             :channel :groundshock/fx-update
                             :mode :update
                             :charge-ticks 7}]
              [:groundshock {:owner-key [:ctx "ctx-1"]
                             :ctx-id "ctx-1"
                             :channel :groundshock/fx-perform
                             :mode :perform}]
              [:groundshock {:owner-key [:ctx "ctx-1"]
                             :ctx-id "ctx-1"
                             :channel :groundshock/fx-end
                             :mode :end
                             :performed? false}]]
             @hand-enqueued*))
      (is (= [[:groundshock {:mode :perform
                             :affected-blocks [{:x 1 :y 2 :z 3 :block-id "minecraft:stone"}]
                             :broken-blocks [{:x 1 :y 2 :z 3}]}
               {:ctx-id "ctx-1" :channel :groundshock/fx-perform}]]
             @level-enqueued*)))))

(deftest two-owners-keep-groundshock-hand-state-independent-test
  (let [hand-enqueue-state! @#'cn.li.ac.content.ability.vecmanip.groundshock-fx/hand-enqueue-state!
        hand-tick-state! @#'cn.li.ac.content.ability.vecmanip.groundshock-fx/hand-tick-state!
        pitch-deltas* (atom [])]
    (with-redefs [hand-effects/add-camera-pitch-delta! (fn
                                                         ([delta]
                                                          (swap! pitch-deltas* conj delta)
                                                          nil)
                                                         ([_owner delta]
                                                          (swap! pitch-deltas* conj delta)
                                                          nil))]
      (hand-effects/update-effect-state! :groundshock hand-enqueue-state! {:owner-key [:ctx "ctx-a"] :ctx-id "ctx-a" :mode :start})
      (hand-effects/update-effect-state! :groundshock hand-enqueue-state! {:owner-key [:ctx "ctx-b"] :ctx-id "ctx-b" :mode :start})
      (hand-effects/update-effect-state! :groundshock hand-enqueue-state! {:owner-key [:ctx "ctx-a"] :ctx-id "ctx-a" :mode :update :charge-ticks 2})
      (hand-effects/update-effect-state! :groundshock hand-enqueue-state! {:owner-key [:ctx "ctx-b"] :ctx-id "ctx-b" :mode :update :charge-ticks 4})
      (let [snapshot (gfx/groundshock-fx-snapshot)]
        (is (= 2 (:charge-ticks (get (:hand-state snapshot) [:ctx "ctx-a"]))))
        (is (= 4 (:charge-ticks (get (:hand-state snapshot) [:ctx "ctx-b"]))))
        (is (= 6 (count @pitch-deltas*))))
      (hand-effects/update-effect-state! :groundshock hand-enqueue-state! {:owner-key [:ctx "ctx-a"] :ctx-id "ctx-a" :mode :perform})
      (hand-effects/update-effect-state! :groundshock hand-enqueue-state! {:owner-key [:ctx "ctx-b"] :ctx-id "ctx-b" :mode :end :performed? false})
      (hand-effects/update-effect-state! :groundshock hand-tick-state!)
      (let [snapshot (gfx/groundshock-fx-snapshot)]
        (is (= 3 (:perform-ticks (get (:hand-state snapshot) [:ctx "ctx-a"]))))
        (is (nil? (get (:hand-state snapshot) [:ctx "ctx-b"]))))
      (gfx/clear-groundshock-owner! [:ctx "ctx-a"])
      (is (empty? (:hand-state (gfx/groundshock-fx-snapshot)))))))

(deftest groundshock-fx-runtime-isolation-test
  (binding [runtime-hooks/*client-session-id* :test-session]
    (let [runtime-a (hand-effects/create-hand-effect-runtime)
          runtime-b (hand-effects/create-hand-effect-runtime)
          hand-enqueue-state! @#'cn.li.ac.content.ability.vecmanip.groundshock-fx/hand-enqueue-state!]
      (with-redefs [hand-effects/add-camera-pitch-delta! (fn [& _] nil)]
        (hand-effects/call-with-hand-effect-runtime
          runtime-a
          (fn []
            (hand-effects/update-effect-state! :groundshock hand-enqueue-state! {:owner-key [:ctx "ctx-a"] :ctx-id "ctx-a" :mode :start})
            (is (= #{[:ctx "ctx-a"]}
                   (set (keys (:hand-state (gfx/groundshock-fx-snapshot))))))))
        (hand-effects/call-with-hand-effect-runtime
          runtime-b
          (fn []
            (is (= {:hand-state {}}
                   (gfx/groundshock-fx-snapshot)))
            (hand-effects/update-effect-state! :groundshock hand-enqueue-state! {:owner-key [:ctx "ctx-b"] :ctx-id "ctx-b" :mode :start})
            (is (= #{[:ctx "ctx-b"]}
                   (set (keys (:hand-state (gfx/groundshock-fx-snapshot))))))))
        (hand-effects/call-with-hand-effect-runtime
          runtime-a
          (fn []
            (is (= #{[:ctx "ctx-a"]}
                   (set (keys (:hand-state (gfx/groundshock-fx-snapshot))))))))))))

(deftest groundshock-fx-snapshot-default-without-registered-state-test
  (is (= {:hand-state {}}
         (gfx/groundshock-fx-snapshot))))

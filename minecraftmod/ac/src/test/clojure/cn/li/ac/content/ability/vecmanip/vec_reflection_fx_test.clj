(ns cn.li.ac.content.ability.vecmanip.vec-reflection-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.content.ability.vecmanip.vec-reflection-fx :as vrfx]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]))

(defn- reset-fixture [f]
  (vrfx/reset-vec-reflection-fx-for-test!)
  (f)
  (vrfx/reset-vec-reflection-fx-for-test!))

(defn- event [ctx-id payload]
  {:payload payload
   :ctx-id ctx-id
   :channel :vec-reflection/fx-reflect-entity
   :owner-key [:ctx ctx-id]})

(use-fixtures :each reset-fixture)

(deftest enqueue-reflect-entity-requires-reflected-flag-test
  (is (nil? (@#'cn.li.ac.content.ability.vecmanip.vec-reflection-fx/enqueue!
             (event "ctx-main" {:mode :reflect-entity :x 1.0 :y 2.0 :z 3.0 :reflected? false}))))
  (is (empty? (:wave-effects (vrfx/vec-reflection-fx-snapshot))))
  (@#'cn.li.ac.content.ability.vecmanip.vec-reflection-fx/enqueue!
   (event "ctx-main" {:mode :reflect-entity :x 1.0 :y 2.0 :z 3.0 :reflected? true}))
  (let [waves (get (:wave-effects (vrfx/vec-reflection-fx-snapshot)) [:ctx "ctx-main"])]
    (is (= 1 (count waves)))
    (is (= 3.0 (:z (first waves))))))

(deftest init-registers-reflected-flag-through-fx-channel-handler-test
  (let [registered-effect (atom nil)
        registered-handler (atom nil)
        enqueued (atom [])]
    (with-redefs [level-effects/register-level-effect!
                  (fn [effect-id effect-map]
                    (reset! registered-effect [effect-id effect-map])
                    nil)
                  fx-registry/register-fx-channels!
                  (fn [channel-keys handler-fn]
                    (reset! registered-handler {:channels channel-keys
                                                :handler handler-fn})
                    nil)
                  level-effects/enqueue-level-effect!
                  (fn [effect-id payload fx-context]
                    (swap! enqueued conj [effect-id payload fx-context])
                    nil)]
      (vrfx/init!)
      (is (= :vec-reflection (first @registered-effect)))
      (is (= #{:vec-reflection/fx-start
               :vec-reflection/fx-end
               :vec-reflection/fx-reflect-entity
               :vec-reflection/fx-play}
             (set (:channels @registered-handler))))
      ((:handler @registered-handler) "ctx-1" :vec-reflection/fx-reflect-entity
       {:x 1.0 :y 2.0 :z 3.0 :reflected? true})
      ((:handler @registered-handler) "ctx-1" :vec-reflection/fx-reflect-entity
       {:x 4.0 :y 5.0 :z 6.0 :reflected? false})
      (is (= [[:vec-reflection {:mode :reflect-entity
                                :x 1.0
                                :y 2.0
                                :z 3.0
                                :reflected? true}
               {:ctx-id "ctx-1" :channel :vec-reflection/fx-reflect-entity}]
              [:vec-reflection {:mode :reflect-entity
                                :x 4.0
                                :y 5.0
                                :z 6.0
                                :reflected? false}
               {:ctx-id "ctx-1" :channel :vec-reflection/fx-reflect-entity}]]
              @enqueued)))))

(deftest two-owners-keep-vec-reflection-state-and-waves-independent-test
  (let [enqueue! @#'cn.li.ac.content.ability.vecmanip.vec-reflection-fx/enqueue!]
    (enqueue! (event "ctx-a" {:mode :start}))
    (enqueue! (event "ctx-b" {:mode :start}))
    (enqueue! (event "ctx-a" {:mode :reflect-entity :x 1.0 :y 2.0 :z 3.0 :reflected? true}))
    (enqueue! (event "ctx-b" {:mode :reflect-entity :x 4.0 :y 5.0 :z 6.0 :reflected? true}))
    (let [snapshot (vrfx/vec-reflection-fx-snapshot)]
      (is (= #{[:ctx "ctx-a"] [:ctx "ctx-b"]}
             (set (keys (:effect-state snapshot)))))
      (is (= 1 (count (get (:wave-effects snapshot) [:ctx "ctx-a"]))))
      (is (= 1 (count (get (:wave-effects snapshot) [:ctx "ctx-b"]))))
      (vrfx/clear-vec-reflection-owner! [:ctx "ctx-a"])
      (let [after-clear (vrfx/vec-reflection-fx-snapshot)]
        (is (nil? (get (:effect-state after-clear) [:ctx "ctx-a"])))
        (is (= 1 (count (get (:wave-effects after-clear) [:ctx "ctx-b"]))))))))

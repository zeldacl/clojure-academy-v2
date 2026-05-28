(ns cn.li.ac.content.ability.vecmanip.vec-accel-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.content.ability.vecmanip.vec-accel-fx :as vafx]))

(defn- with-fresh-vec-accel-fx-runtime [f]
  (vafx/call-with-vec-accel-fx-runtime
    (vafx/create-vec-accel-fx-runtime)
    (fn []
      (try
        (f)
        (finally
          (vafx/reset-vec-accel-fx-for-test!))))))

(defn- event [ctx-id payload]
  {:payload payload
   :ctx-id ctx-id
   :channel :vec-accel/fx-update
   :owner-key [:ctx ctx-id]})

(use-fixtures :each with-fresh-vec-accel-fx-runtime)

(deftest update-keeps-preview-state-per-owner-test
  (let [enqueue! @#'cn.li.ac.content.ability.vecmanip.vec-accel-fx/enqueue!]
    (enqueue! (event "ctx-a" {:mode :start}))
    (enqueue! (event "ctx-b" {:mode :start}))
    (enqueue! (event "ctx-a" {:mode :update
                               :charge-ticks 12
                               :can-perform? true
                               :look-dir {:x 1.0 :y 0.0 :z 0.0}
                               :init-vel {:x 1.0 :y 0.5 :z 0.0}}))
    (enqueue! (event "ctx-b" {:mode :update
                               :charge-ticks 3
                               :can-perform? false
                               :look-dir {:x 0.0 :y 0.0 :z 1.0}
                               :init-vel {:x 0.0 :y 0.5 :z 1.0}}))
    (let [snapshot (vafx/vec-accel-fx-snapshot)]
      (is (= 12 (:charge-ticks (get (:effect-state snapshot) [:ctx "ctx-a"]))))
      (is (= 3 (:charge-ticks (get (:effect-state snapshot) [:ctx "ctx-b"]))))
      (vafx/clear-vec-accel-owner! [:ctx "ctx-a"])
      (let [after-clear (vafx/vec-accel-fx-snapshot)]
        (is (nil? (get (:effect-state after-clear) [:ctx "ctx-a"])))
        (is (some? (get (:effect-state after-clear) [:ctx "ctx-b"])))))))

(deftest init-registers-update-through-fx-channel-handler-test
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
      (vafx/init!)
      (is (= :vec-accel (first @registered-effect)))
      (is (= #{:vec-accel/fx-start
               :vec-accel/fx-update
               :vec-accel/fx-perform
               :vec-accel/fx-end}
             (set (:channels @registered-handler))))
      ((:handler @registered-handler) "ctx-1" :vec-accel/fx-update
       {:charge-ticks 9
        :can-perform? true
        :look-dir {:x 1.0 :y 0.0 :z 0.0}
        :init-vel {:x 0.0 :y 0.5 :z 1.0}})
      (is (= [[:vec-accel {:mode :update
                           :charge-ticks 9
                           :can-perform? true
                           :look-dir {:x 1.0 :y 0.0 :z 0.0}
                           :init-vel {:x 0.0 :y 0.5 :z 1.0}}
               {:ctx-id "ctx-1" :channel :vec-accel/fx-update}]]
             @enqueued)))))

(deftest vec-accel-fx-runtime-isolation-test
  (let [runtime-a (vafx/create-vec-accel-fx-runtime)
        runtime-b (vafx/create-vec-accel-fx-runtime)
        enqueue! @#'cn.li.ac.content.ability.vecmanip.vec-accel-fx/enqueue!]
    (vafx/call-with-vec-accel-fx-runtime
      runtime-a
      (fn []
        (enqueue! (event "ctx-a" {:mode :start}))
        (enqueue! (event "ctx-a" {:mode :update :charge-ticks 5 :can-perform? true
                                   :look-dir {:x 1.0 :y 0.0 :z 0.0}
                                   :init-vel {:x 1.0 :y 0.5 :z 0.0}}))
        (is (= 5 (:charge-ticks (get (:effect-state (vafx/vec-accel-fx-snapshot)) [:ctx "ctx-a"]))))))
    (vafx/call-with-vec-accel-fx-runtime
      runtime-b
      (fn []
        (is (= {:effect-state {}}
               (vafx/vec-accel-fx-snapshot)))
        (enqueue! (event "ctx-b" {:mode :start}))
        (is (= #{[:ctx "ctx-b"]}
               (set (keys (:effect-state (vafx/vec-accel-fx-snapshot))))))))
    (vafx/call-with-vec-accel-fx-runtime
      runtime-a
      (fn []
        (is (= 5 (:charge-ticks (get (:effect-state (vafx/vec-accel-fx-snapshot)) [:ctx "ctx-a"]))))))))

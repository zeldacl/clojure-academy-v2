(ns cn.li.ac.content.ability.vecmanip.vec-deviation-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.content.ability.vecmanip.vec-deviation-fx :as vdfx]))

(defn- with-fresh-vec-deviation-fx-runtime [f]
  (vdfx/call-with-vec-deviation-fx-runtime
    (vdfx/create-vec-deviation-fx-runtime)
    (fn []
      (try
        (f)
        (finally
          (vdfx/reset-vec-deviation-fx-for-test!))))))

(defn- event [ctx-id payload]
  {:payload payload
   :ctx-id ctx-id
   :channel :vec-deviation/fx-stop-entity
   :owner-key [:ctx ctx-id]})

(use-fixtures :each with-fresh-vec-deviation-fx-runtime)

(deftest enqueue-stop-entity-requires-marked-flag-test
  (is (nil? (@#'cn.li.ac.content.ability.vecmanip.vec-deviation-fx/enqueue!
             (event "ctx-main" {:mode :stop-entity :x 1.0 :y 2.0 :z 3.0 :marked? false}))))
  (is (empty? (:wave-effects (vdfx/vec-deviation-fx-snapshot))))
  (@#'cn.li.ac.content.ability.vecmanip.vec-deviation-fx/enqueue!
   (event "ctx-main" {:mode :stop-entity :x 1.0 :y 2.0 :z 3.0 :marked? true}))
  (let [waves (get (:wave-effects (vdfx/vec-deviation-fx-snapshot)) [:ctx "ctx-main"])]
    (is (= 1 (count waves)))
    (is (= 3.0 (:z (first waves))))))

(deftest init-registers-marked-flag-through-fx-channel-handler-test
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
      (vdfx/init!)
      (is (= :vec-deviation (first @registered-effect)))
      (is (= #{:vec-deviation/fx-start
               :vec-deviation/fx-end
               :vec-deviation/fx-stop-entity
               :vec-deviation/fx-play}
             (set (:channels @registered-handler))))
      ((:handler @registered-handler) "ctx-1" :vec-deviation/fx-stop-entity
       {:x 1.0 :y 2.0 :z 3.0 :marked? true})
      ((:handler @registered-handler) "ctx-1" :vec-deviation/fx-stop-entity
       {:x 4.0 :y 5.0 :z 6.0 :marked? false})
      (is (= [[:vec-deviation {:mode :stop-entity
                               :x 1.0
                               :y 2.0
                               :z 3.0
                               :marked? true}
               {:ctx-id "ctx-1" :channel :vec-deviation/fx-stop-entity}]
              [:vec-deviation {:mode :stop-entity
                               :x 4.0
                               :y 5.0
                               :z 6.0
                               :marked? false}
               {:ctx-id "ctx-1" :channel :vec-deviation/fx-stop-entity}]]
             @enqueued)))))

(deftest two-owners-keep-vec-deviation-state-and-waves-independent-test
  (let [enqueue! @#'cn.li.ac.content.ability.vecmanip.vec-deviation-fx/enqueue!]
    (enqueue! (event "ctx-a" {:mode :start}))
    (enqueue! (event "ctx-b" {:mode :start}))
    (enqueue! (event "ctx-a" {:mode :stop-entity :x 1.0 :y 64.0 :z 1.0 :marked? true}))
    (enqueue! (event "ctx-b" {:mode :stop-entity :x 2.0 :y 64.0 :z 2.0 :marked? true}))
    (let [snapshot (vdfx/vec-deviation-fx-snapshot)]
      (is (:active? (get (:effect-state snapshot) [:ctx "ctx-a"])))
      (is (:active? (get (:effect-state snapshot) [:ctx "ctx-b"])))
      (is (= 1 (count (get (:wave-effects snapshot) [:ctx "ctx-a"]))))
      (is (= 1 (count (get (:wave-effects snapshot) [:ctx "ctx-b"]))))
      (vdfx/clear-vec-deviation-owner! [:ctx "ctx-a"])
      (let [after-clear (vdfx/vec-deviation-fx-snapshot)]
        (is (nil? (get (:effect-state after-clear) [:ctx "ctx-a"])))
        (is (nil? (get (:wave-effects after-clear) [:ctx "ctx-a"])))
        (is (:active? (get (:effect-state after-clear) [:ctx "ctx-b"])))))))

(deftest vec-deviation-fx-runtime-isolation-test
  (let [runtime-a (vdfx/create-vec-deviation-fx-runtime)
        runtime-b (vdfx/create-vec-deviation-fx-runtime)
        enqueue! @#'cn.li.ac.content.ability.vecmanip.vec-deviation-fx/enqueue!]
    (vdfx/call-with-vec-deviation-fx-runtime
      runtime-a
      (fn []
        (enqueue! (event "ctx-a" {:mode :start}))
        (enqueue! (event "ctx-a" {:mode :stop-entity :x 1.0 :y 64.0 :z 1.0 :marked? true}))
        (is (= 1 (count (get (:wave-effects (vdfx/vec-deviation-fx-snapshot)) [:ctx "ctx-a"]))))))
    (vdfx/call-with-vec-deviation-fx-runtime
      runtime-b
      (fn []
        (is (= {:effect-state {}
                :wave-effects {}}
               (vdfx/vec-deviation-fx-snapshot)))
        (enqueue! (event "ctx-b" {:mode :start}))
        (is (= #{[:ctx "ctx-b"]}
               (set (keys (:effect-state (vdfx/vec-deviation-fx-snapshot))))))))
    (vdfx/call-with-vec-deviation-fx-runtime
      runtime-a
      (fn []
        (is (= 1 (count (get (:wave-effects (vdfx/vec-deviation-fx-snapshot)) [:ctx "ctx-a"]))))))))

(deftest vec-deviation-fx-runtime-required-without-binding-test
  (vdfx/call-with-vec-deviation-fx-runtime nil
    (fn []
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"runtime is not bound"
            (vdfx/vec-deviation-fx-snapshot))))))

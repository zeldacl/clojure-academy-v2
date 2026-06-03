(ns cn.li.ac.content.ability.meltdowner.rad-intensify-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.content.ability.meltdowner.rad-intensify-fx :as rad-fx]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(defn- reset-fixture [f]
  (binding [runtime-hooks/*client-session-id* :test-session]
    (level-effects/call-with-level-effect-runtime
      (level-effects/create-level-effect-runtime)
      (fn []
        (try
          (level-effects/reset-level-effect-registry-for-test!)
          (rad-fx/reset-rad-intensify-fx-for-test!)
          (f)
          (finally
            (rad-fx/reset-rad-intensify-fx-for-test!)
            (level-effects/reset-level-effect-registry-for-test!)))))))

(use-fixtures :each reset-fixture)

(deftest fx-handler-routes-mark-event-test
  (let [handler* (atom nil)
        enqueued* (atom [])]
    (with-redefs [level-effects/register-level-effect! (fn [& _] nil)
                  fx-registry/register-fx-channels! (fn [_ handler]
                                                      (reset! handler* handler)
                                                      nil)
                  level-effects/enqueue-level-effect! (fn [effect-id payload fx-context]
                                                        (swap! enqueued* conj [effect-id payload fx-context])
                                                        nil)]
      (rad-fx/init!)
      (@handler* "ctx-rad" :rad-intensify/fx-mark {:target-id "t-1" :ticks-left 60 :x 1.0 :y 64.0 :z 2.0})
      (is (= [[:rad-intensify-mark
               {:target-id "t-1" :ticks-left 60 :x 1.0 :y 64.0 :z 2.0}
               {:ctx-id "ctx-rad" :channel :rad-intensify/fx-mark}]]
             @enqueued*)))))

(deftest mark-ttl-decays-and-expires-test
  (let [enqueue-state! (var-get #'cn.li.ac.content.ability.meltdowner.rad-intensify-fx/enqueue-state!)
        tick-state! (var-get #'cn.li.ac.content.ability.meltdowner.rad-intensify-fx/tick-state!)
        build-plan (var-get #'cn.li.ac.content.ability.meltdowner.rad-intensify-fx/build-plan)]
    (with-redefs [rand-int (fn [_] 2)]
      (level-effects/update-effect-state! :rad-intensify-mark
        enqueue-state!
        {:payload {:target-id "target-1" :ticks-left 3 :x 3.0 :y 65.0 :z 7.0}
         :ctx-id "ctx-a"
         :owner-key [:ctx "ctx-a"]})
      (is (= 3 (get-in (rad-fx/rad-intensify-fx-snapshot)
                       [:marks [[:ctx "ctx-a"] "target-1"] :ticks-left])))
      (is (seq (:ops (build-plan {:x 0.0 :y 65.0 :z 0.0} nil 0))))
      (dotimes [_ 3]
        (level-effects/update-effect-state! :rad-intensify-mark
          (fn [store _]
            (tick-state! store))
          nil))
      (is (empty? (:marks (rad-fx/rad-intensify-fx-snapshot))))
      (is (nil? (build-plan {:x 0.0 :y 65.0 :z 0.0} nil 0))))))

(deftest multi-target-state-isolated-test
  (let [enqueue-state! (var-get #'cn.li.ac.content.ability.meltdowner.rad-intensify-fx/enqueue-state!)
        tick-state! (var-get #'cn.li.ac.content.ability.meltdowner.rad-intensify-fx/tick-state!)]
    (level-effects/update-effect-state! :rad-intensify-mark
      enqueue-state!
      {:payload {:target-id "target-a" :ticks-left 5 :x 1.0 :y 64.0 :z 1.0}
       :ctx-id "ctx-a"
       :owner-key [:ctx "ctx-a"]})
    (level-effects/update-effect-state! :rad-intensify-mark
      enqueue-state!
      {:payload {:target-id "target-b" :ticks-left 1 :x 2.0 :y 64.0 :z 2.0}
       :ctx-id "ctx-b"
       :owner-key [:ctx "ctx-b"]})
    (level-effects/update-effect-state! :rad-intensify-mark
      (fn [store _]
        (tick-state! store))
      nil)
    (let [snapshot (rad-fx/rad-intensify-fx-snapshot)]
      (is (contains? (:marks snapshot) [[:ctx "ctx-a"] "target-a"]))
      (is (not (contains? (:marks snapshot) [[:ctx "ctx-b"] "target-b"]))))))

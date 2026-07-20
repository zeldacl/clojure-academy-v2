(ns cn.li.ac.content.ability.meltdowner.rad-intensify-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.content.ability.meltdowner.rad-intensify-fx :as rad-fx]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(defn- reset-fixture [f]
  (runtime-hooks/with-client-ctx-fn {:session-id :test-session} (fn [] (try
          (level-effects/reset-level-effect-registry-for-test!)
          (rad-fx/reset-fx-for-test!)
          (f)
          (finally
            (rad-fx/reset-fx-for-test!)
            (level-effects/reset-level-effect-registry-for-test!))))))

(use-fixtures :each reset-fixture)

(deftest init-registers-rad-intensify-fx-channel-test
  (let [registered-level* (atom nil)
        registered-topics* (atom #{})]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                         (reset! registered-level* [effect-id effect-map])
                                                         nil)
                  fx-registry/register-fx-channel! (fn [topic _handler]
                                                      (swap! registered-topics* conj topic)
                                                      nil)]
      (rad-fx/init!)
      (is (= :rad-intensify-mark (first @registered-level*)))
      (is (fn? (:enqueue-state-fn (second @registered-level*))))
      (is (= #{:rad-intensify/fx-mark}
             @registered-topics*)))))

(deftest fx-handler-routes-mark-event-test
  (let [handlers* (atom {})
        enqueued* (atom [])]
    (with-redefs [level-effects/register-level-effect! (fn [& _] nil)
                  fx-registry/register-fx-channel! (fn [topic handler]
                                                      (swap! handlers* assoc topic handler)
                                                      nil)
                  level-effects/enqueue-level-effect! (fn [effect-id ctx-id channel payload & opts]
                                                        (swap! enqueued* conj [effect-id ctx-id channel payload opts])
                                                        nil)]
      (rad-fx/init!)
      ((get @handlers* :rad-intensify/fx-mark) "ctx-rad" :rad-intensify/fx-mark {:target-id "t-1" :ticks-left 60 :x 1.0 :y 64.0 :z 2.0})
      (is (= [[:rad-intensify-mark
               {:owner-key [:ctx "ctx-rad"]
                :ctx-id "ctx-rad"
                :channel :rad-intensify/fx-mark
                :target-id "t-1"
                :ticks-left 60
                :x 1.0
                :y 64.0
                :z 2.0}
               {:ctx-id "ctx-rad"
                :channel :rad-intensify/fx-mark
                :owner-key [:ctx "ctx-rad"]}]]
             @enqueued*)))))

(deftest mark-ttl-decays-and-expires-test
  (with-redefs [rand-int (fn [_] 2)]
    (arc-beam/enqueue-for-test! :rad-intensify-mark "ctx-a" :rad-intensify/fx-mark
      {:target-id "target-1" :ticks-left 3 :x 3.0 :y 65.0 :z 7.0})
      (is (= 3 (get-in (rad-fx/fx-snapshot)
                       [:marks [[:ctx "ctx-a"] "target-1"] :ticks-left])))
      (is (seq (:ops (arc-beam/effect-build-plan :rad-intensify-mark {:x 0.0 :y 65.0 :z 0.0} nil 0))))
      (dotimes [_ 3]
        (level-effects/update-effect-state! :rad-intensify-mark
          (fn [store] (arc-beam/effect-tick-state! :level :rad-intensify-mark store))))
      (is (empty? (:marks (rad-fx/fx-snapshot))))
      (is (nil? (arc-beam/effect-build-plan :rad-intensify-mark {:x 0.0 :y 65.0 :z 0.0} nil 0)))))

(deftest multi-target-state-isolated-test
  (arc-beam/enqueue-for-test! :rad-intensify-mark "ctx-a" :rad-intensify/fx-mark
    {:target-id "target-a" :ticks-left 5 :x 1.0 :y 64.0 :z 1.0})
  (arc-beam/enqueue-for-test! :rad-intensify-mark "ctx-b" :rad-intensify/fx-mark
    {:target-id "target-b" :ticks-left 1 :x 2.0 :y 64.0 :z 2.0})
  (level-effects/update-effect-state! :rad-intensify-mark
    (fn [store] (arc-beam/effect-tick-state! :level :rad-intensify-mark store)))
    (let [snapshot (rad-fx/fx-snapshot)]
      (is (contains? (:marks snapshot) [[:ctx "ctx-a"] "target-a"]))
      (is (not (contains? (:marks snapshot) [[:ctx "ctx-b"] "target-b"])))))

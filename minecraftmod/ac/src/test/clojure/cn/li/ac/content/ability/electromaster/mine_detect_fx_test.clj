(ns cn.li.ac.content.ability.electromaster.mine-detect-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.content.ability.electromaster.mine-detect-fx :as mine-detect-fx]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(defn- reset-fixture [f]
  (runtime-hooks/with-client-ctx {:session-id :test-session}
    (try
      (level-effects/reset-level-effect-registry-for-test!)
      (mine-detect-fx/reset-fx-for-test!)
      (mine-detect-fx/init!)
      (client-sounds/poll-sound-effects!)
      (f)
      (finally
        (mine-detect-fx/reset-fx-for-test!)
        (client-sounds/poll-sound-effects!)
        (level-effects/reset-level-effect-registry-for-test!)))))

(defn- event [ctx-id payload]
  {:payload payload
   :ctx-id ctx-id
   :channel :mine-detect/fx-perform
   :owner-key [:ctx ctx-id]})

(defn- owner-state [ctx-id]
  (get (:effect-state (mine-detect-fx/fx-snapshot)) [:ctx ctx-id]))

(defn- invoke-enqueue!
  [ctx-id channel payload]
  (do
    (level-effects/update-effect-state! :mine-detect
      (fn [store] (arc-beam/enqueue-for-test! :mine-detect ctx-id channel payload)))))

(defn- invoke-tick! []
  (level-effects/update-effect-state! :mine-detect
    (fn [store] (arc-beam/effect-tick-state! :level :mine-detect store))))

(use-fixtures :each reset-fixture)

(deftest init-registers-mine-detect-fx-channels-test
  (let [registered-effect* (atom nil)
        registered-topics* (atom #{})]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                          (reset! registered-effect* [effect-id effect-map])
                                                          nil)
                  fx-registry/register-fx-channel! (fn [topic _handler]
                                                      (swap! registered-topics* conj topic)
                                                      nil)]
      (mine-detect-fx/init!)
      (is (= :mine-detect (first @registered-effect*)))
      (is (= #{:mine-detect/fx-perform :mine-detect/fx-end}
             @registered-topics*)))))

(deftest fx-handler-routes-perform-and-end-test
  (let [handlers* (atom {})
        enqueued* (atom [])]
    (with-redefs [level-effects/register-level-effect! (fn [& _] nil)
                  fx-registry/register-fx-channel! (fn [topic handler]
                                                      (swap! handlers* assoc topic handler)
                                                      nil)
                  level-effects/enqueue-level-effect! (fn [effect-id ctx-id channel payload & opts]
                                                        (swap! enqueued* conj (into [effect-id ctx-id channel payload] opts))
                                                        nil)]
      (mine-detect-fx/init!)
      ((get @handlers* :mine-detect/fx-perform) "ctx" :mine-detect/fx-perform {:range 30.0 :advanced? true :life-ticks 100 :rescan-interval 5})
      ((get @handlers* :mine-detect/fx-end) "ctx" :mine-detect/fx-end {})
      (is (= [[:mine-detect "ctx" :mine-detect/fx-perform
               {:mode :perform :range 30.0 :advanced? true :life-ticks 100 :rescan-interval 5}
               :owner-key [:ctx "ctx"]]
              [:mine-detect "ctx" :mine-detect/fx-end
               {:mode :end}
               :owner-key [:ctx "ctx"]]]
             @enqueued*)))))

(deftest enqueue-perform-queues-sound-and-initializes-state-test
  (let [sounds* (atom [])]
    (with-redefs [client-sounds/queue-current-sound-effect! (fn [payload]
                                                               (swap! sounds* conj payload)
                                                               nil)]
      (invoke-enqueue! "ctx-main" :mine-detect/fx-perform
        {:mode :perform :range 31.0 :advanced? true :life-ticks 100 :rescan-interval 5})
      (let [st (owner-state "ctx-main")]
        (is (= true (:active? st)))
        (is (= 28.0 (:range st)))
        (is (= 100 (:life-ticks st)))
        (is (= 5 (:rescan-interval st))))
      (is (= 1 (count @sounds*)))
      (is (= "my_mod:em.minedetect" (:sound-id (first @sounds*)))))))

(deftest tick-expires-effect-at-life-cap-test
  (invoke-enqueue! "ctx-main" :mine-detect/fx-perform
    {:mode :perform :range 20.0 :advanced? false :life-ticks 3 :rescan-interval 1})
  (invoke-tick!)
  (is (some? (owner-state "ctx-main")))
  (invoke-tick!)
  (is (some? (owner-state "ctx-main")))
  (invoke-tick!)
  (is (nil? (owner-state "ctx-main"))))

(deftest build-plan-rescans-every-5-ticks-and-applies-advanced-colors-test
  (let [
        query-count* (atom 0)
        query-fn (fn [_x _y _z _radius _predicate]
                   (swap! query-count* inc)
                   [{:x 1 :y 60 :z 1 :block-id "minecraft:coal_ore"}
                    {:x 2 :y 60 :z 2 :block-id "minecraft:diamond_ore"}])
        hand-center {:x 0.0 :y 64.0 :z 0.0}]
    (invoke-enqueue! "ctx-main" :mine-detect/fx-perform
      {:mode :perform :range 24.0 :advanced? true :life-ticks 100 :rescan-interval 5})
    (let [plan0 (arc-beam/effect-build-plan :mine-detect nil hand-center 0 query-fn)
          colors (set (map :color (:ops plan0)))]
      (is (seq (:ops plan0)))
      (is (= 1 @query-count*))
      (is (>= (count colors) 2)))

    (dotimes [_ 4] (invoke-tick!))
    (arc-beam/effect-build-plan :mine-detect nil hand-center 1 query-fn)
    (is (= 1 @query-count*))

    (invoke-tick!)
    (arc-beam/effect-build-plan :mine-detect nil hand-center 2 query-fn)
    (is (= 2 @query-count*))))

(deftest build-plan-non-advanced-uses-single-color-test
  (let [
        query-fn (fn [_x _y _z _radius _predicate]
                   [{:x 1 :y 60 :z 1 :block-id "minecraft:coal_ore"}])
        hand-center {:x 0.0 :y 64.0 :z 0.0}]
    (invoke-enqueue! "ctx-main" :mine-detect/fx-perform
      {:mode :perform :range 24.0 :advanced? false :life-ticks 100 :rescan-interval 5})
    (let [plan (arc-beam/effect-build-plan :mine-detect nil hand-center 0 query-fn)
          colors (set (map :color (:ops plan)))]
      (is (seq (:ops plan)))
      (is (= 1 (count colors))))))

(deftest two-owners-keep-mine-detect-state-independent-test
  (invoke-enqueue! "ctx-a" :mine-detect/fx-perform
    {:mode :perform :range 8.0 :advanced? false :life-ticks 10 :rescan-interval 1})
  (invoke-enqueue! "ctx-b" :mine-detect/fx-perform
    {:mode :perform :range 31.0 :advanced? true :life-ticks 20 :rescan-interval 2})
  (is (= 8.0 (:range (owner-state "ctx-a"))))
  (is (= 28.0 (:range (owner-state "ctx-b"))))
  (mine-detect-fx/clear-fx-owner! [:ctx "ctx-a"])
  (is (nil? (owner-state "ctx-a")))
  (is (some? (owner-state "ctx-b"))))

(deftest fx-snapshot-default-without-registered-state-test
  (is (= {:effect-state {}}
         (mine-detect-fx/fx-snapshot))))

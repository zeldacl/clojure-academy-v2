(ns cn.li.ac.content.ability.electromaster.mine-detect-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.content.ability.electromaster.mine-detect-fx :as mine-detect-fx]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(defn- reset-fixture [f]
  (binding [runtime-hooks/*client-session-id* :test-session]
    (level-effects/call-with-level-effect-runtime
      (level-effects/create-level-effect-runtime)
      (fn []
        (try
          (level-effects/reset-level-effect-registry-for-test!)
          (mine-detect-fx/reset-mine-detect-fx-for-test!)
          (client-sounds/poll-sound-effects!)
          (f)
          (finally
            (mine-detect-fx/reset-mine-detect-fx-for-test!)
            (client-sounds/poll-sound-effects!)
            (level-effects/reset-level-effect-registry-for-test!)))))))

(defn- event [ctx-id payload]
  {:payload payload
   :ctx-id ctx-id
   :channel :mine-detect/fx-perform
   :owner-key [:ctx ctx-id]})

(defn- owner-state [ctx-id]
  (get (:effect-state (mine-detect-fx/mine-detect-fx-snapshot)) [:ctx ctx-id]))

(use-fixtures :each reset-fixture)

(deftest fx-handler-routes-perform-and-end-test
  (let [handler* (atom nil)
        enqueued* (atom [])]
    (with-redefs [level-effects/register-level-effect! (fn [& _] nil)
                  fx-registry/register-fx-channels! (fn [_ handler]
                                                      (reset! handler* handler)
                                                      nil)
                  level-effects/enqueue-level-effect! (fn [effect-id payload fx-context]
                                                        (swap! enqueued* conj [effect-id payload fx-context])
                                                        nil)]
      (mine-detect-fx/init!)
      (@handler* "ctx" :mine-detect/fx-perform {:range 30.0 :advanced? true :life-ticks 100 :rescan-interval 5})
      (@handler* "ctx" :mine-detect/fx-end {})
      (is (= [[:mine-detect {:mode :perform
                             :range 30.0
                             :advanced? true
                             :life-ticks 100
                             :rescan-interval 5}
               {:ctx-id "ctx" :channel :mine-detect/fx-perform}]
              [:mine-detect {:mode :end}
               {:ctx-id "ctx" :channel :mine-detect/fx-end}]]
             @enqueued*)))))

(deftest enqueue-perform-queues-sound-and-initializes-state-test
  (let [enqueue-state! (var-get #'cn.li.ac.content.ability.electromaster.mine-detect-fx/enqueue-state!)
        sounds* (atom [])]
    (with-redefs [client-sounds/queue-current-sound-effect! (fn [payload]
                                                               (swap! sounds* conj payload)
                                                               nil)]
      (level-effects/update-effect-state! :mine-detect enqueue-state!
        (event "ctx-main" {:mode :perform :range 31.0 :advanced? true :life-ticks 100 :rescan-interval 5}))
      (let [st (owner-state "ctx-main")]
        (is (= true (:active? st)))
        (is (= 28.0 (:range st)))
        (is (= 100 (:life-ticks st)))
        (is (= 5 (:rescan-interval st))))
      (is (= 1 (count @sounds*)))
      (is (= "my_mod:em.minedetect" (:sound-id (first @sounds*)))))))

(deftest tick-expires-effect-at-life-cap-test
  (let [enqueue-state! (var-get #'cn.li.ac.content.ability.electromaster.mine-detect-fx/enqueue-state!)
        tick-state! (var-get #'cn.li.ac.content.ability.electromaster.mine-detect-fx/tick-state!)]
    (level-effects/update-effect-state! :mine-detect enqueue-state!
      (event "ctx-main" {:mode :perform :range 20.0 :advanced? false :life-ticks 3 :rescan-interval 1}))
    (level-effects/update-effect-state! :mine-detect tick-state!)
    (is (some? (owner-state "ctx-main")))
    (level-effects/update-effect-state! :mine-detect tick-state!)
    (is (some? (owner-state "ctx-main")))
    (level-effects/update-effect-state! :mine-detect tick-state!)
    (is (nil? (owner-state "ctx-main")))))

(deftest build-plan-rescans-every-5-ticks-and-applies-advanced-colors-test
  (let [enqueue-state! (var-get #'cn.li.ac.content.ability.electromaster.mine-detect-fx/enqueue-state!)
        tick-state! (var-get #'cn.li.ac.content.ability.electromaster.mine-detect-fx/tick-state!)
        build-plan (var-get #'cn.li.ac.content.ability.electromaster.mine-detect-fx/build-plan)
        query-count* (atom 0)
        frame-context {:query-nearby-blocks (fn [_x _y _z _radius _predicate]
                                              (swap! query-count* inc)
                                              [{:x 1 :y 60 :z 1 :block-id "minecraft:coal_ore"}
                                               {:x 2 :y 60 :z 2 :block-id "minecraft:diamond_ore"}])}
        hand-center {:x 0.0 :y 64.0 :z 0.0}]
    (level-effects/update-effect-state! :mine-detect enqueue-state!
      (event "ctx-main" {:mode :perform :range 24.0 :advanced? true :life-ticks 100 :rescan-interval 5}))
    (let [plan0 (build-plan nil hand-center 0 frame-context)
          colors (set (map :color (:ops plan0)))]
      (is (seq (:ops plan0)))
      (is (= 1 @query-count*))
      (is (>= (count colors) 2)))

    (dotimes [_ 4] (level-effects/update-effect-state! :mine-detect tick-state!))
    (build-plan nil hand-center 1 frame-context)
    (is (= 1 @query-count*))

    (level-effects/update-effect-state! :mine-detect tick-state!)
    (build-plan nil hand-center 2 frame-context)
    (is (= 2 @query-count*))))

(deftest build-plan-non-advanced-uses-single-color-test
  (let [enqueue-state! (var-get #'cn.li.ac.content.ability.electromaster.mine-detect-fx/enqueue-state!)
        build-plan (var-get #'cn.li.ac.content.ability.electromaster.mine-detect-fx/build-plan)
        frame-context {:query-nearby-blocks (fn [_x _y _z _radius _predicate]
                                              [{:x 1 :y 60 :z 1 :block-id "minecraft:coal_ore"}
                                               {:x 2 :y 60 :z 2 :block-id "minecraft:diamond_ore"}])}
        hand-center {:x 0.0 :y 64.0 :z 0.0}]
    (level-effects/update-effect-state! :mine-detect enqueue-state!
      (event "ctx-main" {:mode :perform :range 24.0 :advanced? false :life-ticks 100 :rescan-interval 5}))
    (let [plan (build-plan nil hand-center 0 frame-context)
          colors (set (map :color (:ops plan)))]
      (is (seq (:ops plan)))
      (is (= 1 (count colors))))))

(deftest two-owners-keep-mine-detect-state-independent-test
  (let [enqueue-state! (var-get #'cn.li.ac.content.ability.electromaster.mine-detect-fx/enqueue-state!)]
    (level-effects/update-effect-state! :mine-detect enqueue-state!
      (event "ctx-a" {:mode :perform :range 8.0 :advanced? false :life-ticks 10 :rescan-interval 1}))
    (level-effects/update-effect-state! :mine-detect enqueue-state!
      (event "ctx-b" {:mode :perform :range 31.0 :advanced? true :life-ticks 20 :rescan-interval 2}))
    (is (= 8.0 (:range (owner-state "ctx-a"))))
    (is (= 28.0 (:range (owner-state "ctx-b"))))
    (mine-detect-fx/clear-mine-detect-owner! [:ctx "ctx-a"])
    (is (nil? (owner-state "ctx-a")))
    (is (some? (owner-state "ctx-b")))))

(deftest mine-detect-fx-snapshot-default-without-registered-state-test
  (is (= {:effect-state {}}
         (mine-detect-fx/mine-detect-fx-snapshot))))

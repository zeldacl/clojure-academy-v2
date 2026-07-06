(ns cn.li.ac.content.ability.vecmanip.vec-reflection-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.content.ability.vecmanip.vec-reflection-fx :as vrfx]))

(defn- reset-fixture [f]
  (try
        (level-effects/reset-level-effect-registry-for-test!)
        (vrfx/reset-vec-reflection-fx-for-test!)
        (f)
        (finally
          (vrfx/reset-vec-reflection-fx-for-test!)
          (level-effects/reset-level-effect-registry-for-test!))))

(use-fixtures :each reset-fixture)

(defn- event
  [ctx-id payload]
  {:payload payload
   :ctx-id ctx-id
   :channel :vec-reflection/fx-reflect-entity
   :owner-key [:ctx ctx-id]})

(deftest init-registers-owner-aware-vec-reflection-fx-test
  (let [registered-level* (atom nil)
        registered-topics* (atom #{})]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                         (reset! registered-level* [effect-id effect-map])
                                                         nil)
                  fx-registry/register-fx-channel! (fn [topic _handler]
                                                      (swap! registered-topics* conj topic)
                                                      nil)]
      (vrfx/init!)
      (is (= :vec-reflection (first @registered-level*)))
      (is (fn? (:enqueue-state-fn (second @registered-level*))))
      (is (= #{:vec-reflection/fx-start
               :vec-reflection/fx-end
               :vec-reflection/fx-reflect-entity
               :vec-reflection/fx-play}
             @registered-topics*)))))

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

(deftest init-registers-reflected-flag-through-fx-channel-handler-test
  (let [handlers* (atom {})
        enqueued* (atom [])]
    (with-redefs [level-effects/register-level-effect! (fn [& _] nil)
                  fx-registry/register-fx-channel! (fn [topic handler]
                                                      (swap! handlers* assoc topic handler)
                                                      nil)
                  level-effects/enqueue-level-effect! (fn [effect-id ctx-id channel payload & opts]
                                                        (swap! enqueued* conj [effect-id ctx-id channel payload opts])
                                                        nil)
                  client-sounds/queue-current-sound-effect! (fn [& _] nil)]
      (vrfx/init!)
      ((get @handlers* :vec-reflection/fx-reflect-entity) "ctx-1" :vec-reflection/fx-reflect-entity
       {:x 1.0 :y 2.0 :z 3.0 :reflected? true})
      ((get @handlers* :vec-reflection/fx-reflect-entity) "ctx-1" :vec-reflection/fx-reflect-entity
       {:x 4.0 :y 5.0 :z 6.0 :reflected? false})
      (is (= [[:vec-reflection {:mode :reflect-entity
                                :owner-key [:ctx "ctx-1"]
                                :ctx-id "ctx-1"
                                :channel :vec-reflection/fx-reflect-entity
                                :x 1.0
                                :y 2.0
                                :z 3.0
                                :reflected? true}
               {:ctx-id "ctx-1"
                :channel :vec-reflection/fx-reflect-entity
                :owner-key [:ctx "ctx-1"]}]
              [:vec-reflection {:mode :reflect-entity
                                :owner-key [:ctx "ctx-1"]
                                :ctx-id "ctx-1"
                                :channel :vec-reflection/fx-reflect-entity
                                :x 4.0
                                :y 5.0
                                :z 6.0
                                :reflected? false}
               {:ctx-id "ctx-1"
                :channel :vec-reflection/fx-reflect-entity
                :owner-key [:ctx "ctx-1"]}]]
             @enqueued*)))))

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



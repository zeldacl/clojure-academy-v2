(ns cn.li.ac.content.ability.vecmanip.plasma-cannon-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.content.ability.vecmanip.plasma-cannon-fx :as pcfx]))

(defn- reset-fixture [f]
  (try
        (level-effects/reset-level-effect-registry-for-test!)
        (pcfx/reset-plasma-cannon-fx-for-test!)
        (f)
        (finally
          (pcfx/reset-plasma-cannon-fx-for-test!)
          (level-effects/reset-level-effect-registry-for-test!))))

(use-fixtures :each reset-fixture)

(defn- event
  [ctx-id payload]
  {:payload payload
   :ctx-id ctx-id
   :channel :plasma-cannon/fx-update
   :owner-key [:ctx ctx-id]})

(deftest init-registers-plasma-cannon-fx-channels-test
  (let [registered-level* (atom nil)
        registered-topics* (atom #{})]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                         (reset! registered-level* [effect-id effect-map])
                                                         nil)
                  fx-registry/register-fx-channel! (fn [topic _handler]
                                                      (swap! registered-topics* conj topic)
                                                      nil)]
      (pcfx/init!)
      (is (= :plasma-cannon (first @registered-level*)))
      (is (fn? (:enqueue-state-fn (second @registered-level*))))
      (is (= #{:plasma-cannon/fx-start
               :plasma-cannon/fx-update
               :plasma-cannon/fx-perform
               :plasma-cannon/fx-end}
             @registered-topics*)))))

(deftest fx-handler-routes-start-update-perform-end-payloads-test
  (let [handlers* (atom {})
        enqueued* (atom [])]
    (with-redefs [level-effects/register-level-effect! (fn [& _] nil)
                  fx-registry/register-fx-channel! (fn [topic handler]
                                                      (swap! handlers* assoc topic handler)
                                                      nil)
                  level-effects/enqueue-level-effect! (fn [effect-id ctx-id channel payload & opts]
                                                        (swap! enqueued* conj [effect-id ctx-id channel payload opts])
                                                        nil)]
      (pcfx/init!)
      ((get @handlers* :plasma-cannon/fx-start) "ctx-1" :plasma-cannon/fx-start {:charge-pos {:x 1.0 :y 64.0 :z 1.0}})
      ((get @handlers* :plasma-cannon/fx-update) "ctx-1" :plasma-cannon/fx-update {:charge-ticks 24
                                                                :fully-charged? true
                                                                :charge-pos {:x 1.0 :y 64.0 :z 1.0}
                                                                :flight-ticks 2
                                                                :state :go
                                                                :destination {:x 4.0 :y 64.0 :z 4.0}})
      ((get @handlers* :plasma-cannon/fx-perform) "ctx-1" :plasma-cannon/fx-perform {:pos {:x 2.0 :y 65.0 :z 2.0}})
      ((get @handlers* :plasma-cannon/fx-end) "ctx-1" :plasma-cannon/fx-end {:performed? true})
      (is (= [[:plasma-cannon {:mode :start
                               :owner-key [:ctx "ctx-1"]
                               :ctx-id "ctx-1"
                               :channel :plasma-cannon/fx-start
                               :charge-pos {:x 1.0 :y 64.0 :z 1.0}}
               {:ctx-id "ctx-1"
                :channel :plasma-cannon/fx-start
                :owner-key [:ctx "ctx-1"]}]
              [:plasma-cannon {:mode :update
                               :owner-key [:ctx "ctx-1"]
                               :ctx-id "ctx-1"
                               :channel :plasma-cannon/fx-update
                               :charge-ticks 24
                               :fully-charged? true
                               :charge-pos {:x 1.0 :y 64.0 :z 1.0}
                               :flight-ticks 2
                               :state :go
                               :destination {:x 4.0 :y 64.0 :z 4.0}}
               {:ctx-id "ctx-1"
                :channel :plasma-cannon/fx-update
                :owner-key [:ctx "ctx-1"]}]
              [:plasma-cannon {:mode :perform
                               :owner-key [:ctx "ctx-1"]
                               :ctx-id "ctx-1"
                               :channel :plasma-cannon/fx-perform
                               :pos {:x 2.0 :y 65.0 :z 2.0}}
               {:ctx-id "ctx-1"
                :channel :plasma-cannon/fx-perform
                :owner-key [:ctx "ctx-1"]}]
              [:plasma-cannon {:mode :end
                               :owner-key [:ctx "ctx-1"]
                               :ctx-id "ctx-1"
                               :channel :plasma-cannon/fx-end
                               :performed? true}
               {:ctx-id "ctx-1"
                :channel :plasma-cannon/fx-end
                :owner-key [:ctx "ctx-1"]}]]
             @enqueued*)))))

(deftest tick-build-plan-and-perform-effects-test
  (let [
        sound-calls* (atom [])
        particle-calls* (atom [])]
    (with-redefs [client-particles/current-effect-owner (fn [] {:client-session-id "plasma-cannon-test"})
                  client-sounds/queue-sound-effect! (fn [& args]
                                                      (swap! sound-calls* conj args)
                                                      nil)
                  client-particles/queue-particle-effect! (fn [& args]
                                                            (swap! particle-calls* conj args)
                                                            nil)
                  rand (fn [] 0.5)]
      (arc-beam/enqueue-for-test! :plasma-cannon "ctx-main" :plasma-cannon/fx-update {:mode :start :charge-pos {:x 1.0 :y 64.0 :z 1.0}})
      (arc-beam/enqueue-for-test! :plasma-cannon "ctx-main" :plasma-cannon/fx-update {:mode :update
                             :charge-ticks 24
                             :fully-charged? true
                             :charge-pos {:x 1.0 :y 64.0 :z 1.0}
                             :flight-ticks 2
                             :state :go
                             :destination {:x 4.0 :y 64.0 :z 4.0}})
      (dotimes [_ 10]
        (level-effects/update-effect-state! :plasma-cannon
          (fn [store] (arc-beam/effect-tick-state! :level :plasma-cannon store))
          nil))
      (let [plan (arc-beam/effect-build-plan :plasma-cannon nil nil 0)]
        (is (= 3 (count @sound-calls*)))
        (is (= 10 (count @particle-calls*)))
        (is (= 1 (count (:ops plan))))
        (is (= 10 (get-in (pcfx/plasma-cannon-fx-snapshot)
                          [:effect-state [:ctx "ctx-main"] :ticks]))))
      (reset! sound-calls* [])
      (reset! particle-calls* [])
      (arc-beam/enqueue-for-test! :plasma-cannon "ctx-main" :plasma-cannon/fx-update {:mode :perform :pos {:x 2.0 :y 65.0 :z 2.0}})
      (is (= 1 (count @sound-calls*)))
      (is (= 13 (count @particle-calls*))))))



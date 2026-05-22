(ns cn.li.ac.content.ability.vecmanip.blood-retrograde-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.content.ability.vecmanip.blood-retrograde-fx :as brfx]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(defn- reset-fixture [f]
  (reset! @#'cn.li.ac.content.ability.vecmanip.blood-retrograde-fx/effect-state nil)
  (reset! @#'cn.li.ac.content.ability.vecmanip.blood-retrograde-fx/splashes [])
  (reset! @#'cn.li.ac.content.ability.vecmanip.blood-retrograde-fx/sprays [])
  (f)
  (reset! @#'cn.li.ac.content.ability.vecmanip.blood-retrograde-fx/effect-state nil)
  (reset! @#'cn.li.ac.content.ability.vecmanip.blood-retrograde-fx/splashes [])
  (reset! @#'cn.li.ac.content.ability.vecmanip.blood-retrograde-fx/sprays []))

(use-fixtures :each reset-fixture)

(deftest init-registers-blood-retrograde-fx-channels-test
  (let [registered-effect (atom nil)
        registered-handler (atom nil)]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                         (reset! registered-effect [effect-id effect-map])
                                                         nil)
                  fx-registry/register-fx-channels! (fn [channels handler]
                                                      (reset! registered-handler {:channels channels
                                                                                  :handler handler})
                                                      nil)]
      (brfx/init!)
      (is (= :blood-retrograde (first @registered-effect)))
      (is (= #{:blood-retrograde/fx-start
               :blood-retrograde/fx-update
               :blood-retrograde/fx-end
               :blood-retrograde/fx-perform}
             (set (:channels @registered-handler)))))))

(deftest fx-handler-routes-start-update-perform-end-test
  (let [handler* (atom nil)
        enqueue-fn* (atom nil)
        enqueued-effects* (atom [])
        sound-calls* (atom [])]
    (with-redefs [level-effects/register-level-effect! (fn [_effect-id effect-map]
                                                         (reset! enqueue-fn* (:enqueue-fn effect-map))
                                                         nil)
                  fx-registry/register-fx-channels! (fn [_ handler]
                                                      (reset! handler* handler)
                                                      nil)
                  level-effects/enqueue-level-effect! (fn [effect-id payload]
                                                        (swap! enqueued-effects* conj [effect-id payload])
                                                        (@enqueue-fn* payload)
                                                        nil)
                  client-sounds/queue-sound-effect! (fn [payload]
                                                      (swap! sound-calls* conj payload)
                                                      nil)]
      (brfx/init!)
      (@handler* "ctx-1" :blood-retrograde/fx-start nil)
      (@handler* "ctx-1" :blood-retrograde/fx-update {:ticks 7 :charge-ratio 0.35})
      (@handler* "ctx-1" :blood-retrograde/fx-perform {:sound-pos {:x 1.0 :y 2.0 :z 3.0}
                                                       :splashes [{:x 1.0 :y 2.0 :z 3.0 :size 1.4}]
                                                       :sprays [{:x 4.0 :y 5.0 :z 6.0 :face :up :size 1.2 :rotation 0.0
                                                                :offset-u 0.0 :offset-v 0.0 :texture-id 1}]})
      (@handler* "ctx-1" :blood-retrograde/fx-end {:performed? true})
      (is (= [[:blood-retrograde {:mode :start}]
              [:blood-retrograde {:mode :update :ticks 7 :charge-ratio 0.35}]
              [:blood-retrograde {:mode :perform
                                  :sound-pos {:x 1.0 :y 2.0 :z 3.0}
                                  :splashes [{:x 1.0 :y 2.0 :z 3.0 :size 1.4}]
                                  :sprays [{:x 4.0 :y 5.0 :z 6.0 :face :up :size 1.2 :rotation 0.0
                                            :offset-u 0.0 :offset-v 0.0 :texture-id 1}]}]
              [:blood-retrograde {:mode :end :performed? true}]]
             @enqueued-effects*))
      (is (= 1 (count @sound-calls*)))
      (is (= "my_mod:vecmanip.blood_retro" (:sound-id (first @sound-calls*)))))))

(defn- approx= [expected actual]
  (< (Math/abs (- (double expected) (double actual))) 1.0e-6))

(deftest walk-speed-curve-and-tick-cleanup-test
  (let [build-plan @#'cn.li.ac.content.ability.vecmanip.blood-retrograde-fx/build-plan
        tick! @#'cn.li.ac.content.ability.vecmanip.blood-retrograde-fx/tick!]
    (reset! @#'cn.li.ac.content.ability.vecmanip.blood-retrograde-fx/effect-state
            {:active? true :ticks 0 :charge-ratio 0.0 :performed? false})
        (is (approx= 0.1 (:local-walk-speed (build-plan {:x 0.0 :y 0.0 :z 0.0}
                    {:x 0.0 :y 0.0 :z 0.0}
                    0))))
    (swap! @#'cn.li.ac.content.ability.vecmanip.blood-retrograde-fx/effect-state assoc :ticks 10)
        (is (approx= 0.0535 (:local-walk-speed (build-plan {:x 0.0 :y 0.0 :z 0.0}
                       {:x 0.0 :y 0.0 :z 0.0}
                       10))))
    (swap! @#'cn.li.ac.content.ability.vecmanip.blood-retrograde-fx/effect-state assoc :ticks 20)
        (is (approx= 0.007 (:local-walk-speed (build-plan {:x 0.0 :y 0.0 :z 0.0}
                      {:x 0.0 :y 0.0 :z 0.0}
                      20))))
    (reset! @#'cn.li.ac.content.ability.vecmanip.blood-retrograde-fx/splashes
            [{:ttl 1 :max-ttl 10}])
    (reset! @#'cn.li.ac.content.ability.vecmanip.blood-retrograde-fx/sprays
            [{:ttl 2 :max-ttl 10}])
    (tick!)
        (is (= 21 (:ticks @@#'cn.li.ac.content.ability.vecmanip.blood-retrograde-fx/effect-state)))
    (is (= 0 (count @@#'cn.li.ac.content.ability.vecmanip.blood-retrograde-fx/splashes)))
    (is (= 1 (count @@#'cn.li.ac.content.ability.vecmanip.blood-retrograde-fx/sprays)))))

(ns cn.li.ac.content.ability.vecmanip.vec-reflection-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.content.ability.vecmanip.vec-reflection-fx :as vrfx]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]))

(defn- reset-fixture [f]
  (reset! @#'cn.li.ac.content.ability.vecmanip.vec-reflection-fx/effect-state nil)
  (reset! @#'cn.li.ac.content.ability.vecmanip.vec-reflection-fx/wave-effects [])
  (f)
  (reset! @#'cn.li.ac.content.ability.vecmanip.vec-reflection-fx/effect-state nil)
  (reset! @#'cn.li.ac.content.ability.vecmanip.vec-reflection-fx/wave-effects []))

(use-fixtures :each reset-fixture)

(deftest enqueue-reflect-entity-requires-reflected-flag-test
  (is (nil? (@#'cn.li.ac.content.ability.vecmanip.vec-reflection-fx/enqueue!
             {:mode :reflect-entity :x 1.0 :y 2.0 :z 3.0 :reflected? false})))
  (is (empty? @@#'cn.li.ac.content.ability.vecmanip.vec-reflection-fx/wave-effects))
  (@#'cn.li.ac.content.ability.vecmanip.vec-reflection-fx/enqueue!
   {:mode :reflect-entity :x 1.0 :y 2.0 :z 3.0 :reflected? true})
  (is (= 1 (count @@#'cn.li.ac.content.ability.vecmanip.vec-reflection-fx/wave-effects)))
  (is (= 3.0 (:z (first @@#'cn.li.ac.content.ability.vecmanip.vec-reflection-fx/wave-effects)))))

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
                  (fn [effect-id payload]
                    (swap! enqueued conj [effect-id payload])
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
                                :reflected? true}]
              [:vec-reflection {:mode :reflect-entity
                                :x 4.0
                                :y 5.0
                                :z 6.0
                                :reflected? false}]]
              @enqueued)))))

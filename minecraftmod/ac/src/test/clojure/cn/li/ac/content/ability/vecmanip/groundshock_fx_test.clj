(ns cn.li.ac.content.ability.vecmanip.groundshock-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.content.ability.vecmanip.groundshock-fx :as gfx]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.hand-effects :as hand-effects]
            [cn.li.ac.ability.client.level-effects :as level-effects]))

(defn- reset-fixture [f]
  (reset! @#'cn.li.ac.content.ability.vecmanip.groundshock-fx/hand-state nil)
  (f)
  (reset! @#'cn.li.ac.content.ability.vecmanip.groundshock-fx/hand-state nil))

(use-fixtures :each reset-fixture)

(deftest init-registers-groundshock-fx-channels-test
  (let [registered-level (atom nil)
        registered-hand (atom nil)
        registered-handler (atom nil)]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                         (reset! registered-level [effect-id effect-map])
                                                         nil)
                  hand-effects/register-hand-effect! (fn [effect-id effect-map]
                                                       (reset! registered-hand [effect-id effect-map])
                                                       nil)
                  fx-registry/register-fx-channels! (fn [channels handler]
                                                      (reset! registered-handler {:channels channels
                                                                                 :handler handler})
                                                      nil)]
      (gfx/init!)
      (is (= :groundshock (first @registered-level)))
      (is (= :groundshock (first @registered-hand)))
      (is (= #{:groundshock/fx-start
               :groundshock/fx-update
               :groundshock/fx-perform
               :groundshock/fx-end}
             (set (:channels @registered-handler)))))))

(deftest fx-handler-routes-start-update-perform-end-payloads-test
  (let [handler* (atom nil)
        hand-enqueued* (atom [])
        level-enqueued* (atom [])]
    (with-redefs [level-effects/register-level-effect! (fn [& _] nil)
                  hand-effects/register-hand-effect! (fn [& _] nil)
                  fx-registry/register-fx-channels! (fn [_ handler]
                                                      (reset! handler* handler)
                                                      nil)
                  hand-effects/enqueue-hand-effect! (fn [effect-id payload]
                                                      (swap! hand-enqueued* conj [effect-id payload])
                                                      nil)
                  level-effects/enqueue-level-effect! (fn [effect-id payload]
                                                        (swap! level-enqueued* conj [effect-id payload])
                                                        nil)]
      (gfx/init!)
      (@handler* "ctx-1" :groundshock/fx-start nil)
      (@handler* "ctx-1" :groundshock/fx-update {:charge-ticks 7})
      (@handler* "ctx-1" :groundshock/fx-perform {:affected-blocks [{:x 1 :y 2 :z 3 :block-id "minecraft:stone"}]
                                                   :broken-blocks [{:x 1 :y 2 :z 3}]})
      (@handler* "ctx-1" :groundshock/fx-end {:performed? false})

      (is (= [[:groundshock {:mode :start}]
              [:groundshock {:mode :update :charge-ticks 7}]
              [:groundshock {:mode :perform}]
              [:groundshock {:mode :end :performed? false}]]
             @hand-enqueued*))
      (is (= [[:groundshock {:mode :perform
                             :affected-blocks [{:x 1 :y 2 :z 3 :block-id "minecraft:stone"}]
                             :broken-blocks [{:x 1 :y 2 :z 3}]}]]
             @level-enqueued*)))))

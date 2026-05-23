(ns cn.li.ac.content.ability.teleporter.penetrate-teleport-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.content.ability.teleporter.penetrate-teleport-fx :as pfx]))

(defn- reset-fixture [f]
  (reset! (var-get #'cn.li.ac.content.ability.teleporter.penetrate-teleport-fx/fx-state) nil)
  (f)
  (reset! (var-get #'cn.li.ac.content.ability.teleporter.penetrate-teleport-fx/fx-state) nil))

(use-fixtures :each reset-fixture)

(deftest init-registers-penetrate-fx-channels-test
  (let [registered-level* (atom nil)
        registered-handler* (atom nil)]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                         (reset! registered-level* [effect-id effect-map])
                                                         nil)
                  fx-registry/register-fx-channels! (fn [channels handler]
                                                      (reset! registered-handler* {:channels channels
                                                                                   :handler handler})
                                                      nil)]
      (pfx/init!)
      (is (= :penetrate-teleport (first @registered-level*)))
      (is (= #{:penetrate-tp/fx-start
               :penetrate-tp/fx-update
               :penetrate-tp/fx-perform
               :penetrate-tp/fx-end}
             (set (:channels @registered-handler*)))))))

(deftest fx-handler-routes-update-and-perform-payloads-test
  (let [handler* (atom nil)
        enqueued* (atom [])]
    (with-redefs [level-effects/register-level-effect! (fn [& _] nil)
                  fx-registry/register-fx-channels! (fn [_ handler]
                                                      (reset! handler* handler)
                                                      nil)
                  level-effects/enqueue-level-effect! (fn [effect-id payload]
                                                        (swap! enqueued* conj [effect-id payload])
                                                        nil)]
      (pfx/init!)
      (@handler* "ctx-1" :penetrate-tp/fx-start nil)
      (@handler* "ctx-1" :penetrate-tp/fx-update {:distance 12.0 :available? true :x 1.0 :y 2.0 :z 3.0})
      (@handler* "ctx-1" :penetrate-tp/fx-perform {:x 4.0 :y 5.0 :z 6.0})
      (@handler* "ctx-1" :penetrate-tp/fx-end nil)

      (is (= [[:penetrate-teleport {:mode :start}]
              [:penetrate-teleport {:mode :update :distance 12.0 :available? true :x 1.0 :y 2.0 :z 3.0}]
              [:penetrate-teleport {:mode :perform :x 4.0 :y 5.0 :z 6.0}]
              [:penetrate-teleport {:mode :end}]]
             @enqueued*)))))

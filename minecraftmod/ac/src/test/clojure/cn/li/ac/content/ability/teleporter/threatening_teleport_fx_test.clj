(ns cn.li.ac.content.ability.teleporter.threatening-teleport-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.content.ability.teleporter.threatening-teleport-fx :as tfx]))

(defn- reset-fixture [f]
  (reset! @#'cn.li.ac.content.ability.teleporter.threatening-teleport-fx/fx-state nil)
  (f)
  (reset! @#'cn.li.ac.content.ability.teleporter.threatening-teleport-fx/fx-state nil))

(use-fixtures :each reset-fixture)

(deftest init-registers-threatening-teleport-fx-channels-test
  (let [registered-level* (atom nil)
        registered-handler* (atom nil)]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                         (reset! registered-level* [effect-id effect-map])
                                                         nil)
                  fx-registry/register-fx-channels! (fn [channels handler]
                                                      (reset! registered-handler* {:channels channels
                                                                                   :handler handler})
                                                      nil)]
      (tfx/init!)
      (is (= :threatening-teleport (first @registered-level*)))
      (is (= #{:threatening-tp/fx-start
               :threatening-tp/fx-update
               :threatening-tp/fx-perform
               :threatening-tp/fx-end}
             (set (:channels @registered-handler*)))))))

(deftest fx-handler-routes-start-update-perform-end-payloads-test
  (let [handler* (atom nil)
        enqueued* (atom [])]
    (with-redefs [level-effects/register-level-effect! (fn [& _] nil)
                  fx-registry/register-fx-channels! (fn [_ handler]
                                                      (reset! handler* handler)
                                                      nil)
                  level-effects/enqueue-level-effect! (fn [effect-id payload]
                                                        (swap! enqueued* conj [effect-id payload])
                                                        nil)]
      (tfx/init!)
      (@handler* "ctx-1" :threatening-tp/fx-start nil)
      (@handler* "ctx-1" :threatening-tp/fx-update {:drop-x 1.0 :drop-y 2.0 :drop-z 3.0 :attacked? true})
      (@handler* "ctx-1" :threatening-tp/fx-perform {:start-x 0.0 :start-y 1.0 :start-z 2.0
                                                      :drop-x 3.0 :drop-y 4.0 :drop-z 5.0
                                                      :attacked? false :dropped? true})
      (@handler* "ctx-1" :threatening-tp/fx-end nil)

      (is (= [[:threatening-teleport {:mode :start}]
              [:threatening-teleport {:mode :update :drop-x 1.0 :drop-y 2.0 :drop-z 3.0 :attacked? true}]
              [:threatening-teleport {:mode :perform
                                      :start-x 0.0 :start-y 1.0 :start-z 2.0
                                      :drop-x 3.0 :drop-y 4.0 :drop-z 5.0
                                      :attacked? false :dropped? true}]
              [:threatening-teleport {:mode :end}]]
             @enqueued*)))))

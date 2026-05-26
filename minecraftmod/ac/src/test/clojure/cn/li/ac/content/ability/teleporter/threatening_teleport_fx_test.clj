(ns cn.li.ac.content.ability.teleporter.threatening-teleport-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.content.ability.teleporter.threatening-teleport-fx :as tfx]))

(defn- reset-fixture [f]
  (tfx/reset-threatening-teleport-fx-for-test!)
  (f)
  (tfx/reset-threatening-teleport-fx-for-test!))

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
                  level-effects/enqueue-level-effect! (fn [effect-id payload fx-context]
                                                        (swap! enqueued* conj [effect-id payload fx-context])
                                                        nil)]
      (tfx/init!)
      (@handler* "ctx-1" :threatening-tp/fx-start nil)
      (@handler* "ctx-1" :threatening-tp/fx-update {:drop-x 1.0 :drop-y 2.0 :drop-z 3.0 :attacked? true})
      (@handler* "ctx-1" :threatening-tp/fx-perform {:start-x 0.0 :start-y 1.0 :start-z 2.0
                                                      :drop-x 3.0 :drop-y 4.0 :drop-z 5.0
                                                      :attacked? false :dropped? true})
      (@handler* "ctx-1" :threatening-tp/fx-end nil)

      (is (= [[:threatening-teleport {:mode :start}
               {:ctx-id "ctx-1" :channel :threatening-tp/fx-start}]
              [:threatening-teleport {:mode :update :drop-x 1.0 :drop-y 2.0 :drop-z 3.0 :attacked? true}
               {:ctx-id "ctx-1" :channel :threatening-tp/fx-update}]
              [:threatening-teleport {:mode :perform
                                      :start-x 0.0 :start-y 1.0 :start-z 2.0
                                      :drop-x 3.0 :drop-y 4.0 :drop-z 5.0
                                      :attacked? false :dropped? true}
               {:ctx-id "ctx-1" :channel :threatening-tp/fx-perform}]
              [:threatening-teleport {:mode :end}
               {:ctx-id "ctx-1" :channel :threatening-tp/fx-end}]]
             @enqueued*)))))

(deftest two-owners-keep-threatening-teleport-state-independent-test
  (let [enqueue! @#'cn.li.ac.content.ability.teleporter.threatening-teleport-fx/enqueue!
        event (fn [ctx-id payload]
                {:payload payload
                 :ctx-id ctx-id
                 :channel :threatening-tp/fx-update
                 :owner-key [:ctx ctx-id]})]
    (enqueue! (event "ctx-a" {:mode :start}))
    (enqueue! (event "ctx-b" {:mode :start}))
    (enqueue! (event "ctx-a" {:mode :update :drop-x 1.0 :drop-y 2.0 :drop-z 3.0 :attacked? true}))
    (enqueue! (event "ctx-b" {:mode :update :drop-x 4.0 :drop-y 5.0 :drop-z 6.0 :attacked? false}))
    (let [snapshot (tfx/threatening-teleport-fx-snapshot)]
      (is (true? (:attacked? (get (:fx-state snapshot) [:ctx "ctx-a"]))))
      (is (= {:x 4.0 :y 5.0 :z 6.0}
             (:aim (get (:fx-state snapshot) [:ctx "ctx-b"])))))
    (enqueue! (event "ctx-a" {:mode :end}))
    (let [snapshot (tfx/threatening-teleport-fx-snapshot)]
      (is (nil? (get (:fx-state snapshot) [:ctx "ctx-a"])))
      (is (some? (get (:fx-state snapshot) [:ctx "ctx-b"]))))
    (tfx/clear-threatening-teleport-owner! [:ctx "ctx-b"])
    (is (empty? (:fx-state (tfx/threatening-teleport-fx-snapshot))))))

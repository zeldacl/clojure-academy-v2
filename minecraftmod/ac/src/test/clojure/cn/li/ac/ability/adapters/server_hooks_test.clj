(ns cn.li.ac.ability.adapters.server-hooks-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cn.li.ac.ability.adapters.server-hooks :as server-hooks]
            [cn.li.ac.ability.item-actions :as item-actions]
            [cn.li.ac.ability.registry.event :as evt]
            [cn.li.ac.ability.server.service.context-mgr :as ctx-mgr]
            [cn.li.ac.ability.server.service.resource :as svc-res]
            [cn.li.ac.ability.service.player-state :as ps]))

(defn- reset-registries! [f]
  (let [item-actions-snapshot (item-actions/item-action-registries-snapshot)]
    (try
      (item-actions/reset-item-action-registries-for-test!)
      (f)
      (finally
        (item-actions/reset-item-action-registries-for-test! item-actions-snapshot)))))

(defn- reset-lifecycle-subs! [f]
  (evt/reset-ability-event-subscribers-for-test!)
  (server-hooks/reset-lifecycle-subscriptions-registered-for-test!)
  (try
    (f)
    (finally
      (evt/reset-ability-event-subscribers-for-test!)
      (server-hooks/reset-lifecycle-subscriptions-registered-for-test!))))

(use-fixtures :each reset-registries!)
(use-fixtures :each reset-lifecycle-subs!)

(deftest build-item-use-plan-order-test
  (testing "coin use plans consume, dispatch domain action, then spawn scripted effect"
    (item-actions/register-item-action! "ac:coin" :railgun-coin-throw)
    (item-actions/register-item-entity-spawn! "ac:coin" {:entity-id "entity_coin_throwing" :speed 0.0})
    (let [build-plan (:build-item-use-plan (server-hooks/runtime-server-hooks))
          plan (build-plan "p1" "ac:coin" true :server)]
          (is (= {:kind :consume-item :count 1 :unless-instabuild? true}
            (first (:server-actions plan))))
          (is (= :domain-action (:kind (second (:server-actions plan)))))
          (is (= :railgun-coin-throw (:action (second (:server-actions plan)))))
          (is (number? (get-in plan [:server-actions 1 :payload :timestamp-ms])))
          (is (= {:kind :spawn-scripted-effect :entity-id "entity_coin_throwing" :speed 0.0}
            (nth (:server-actions plan) 2)))
      (is (= [{:kind :notify-local-effect
               :event-key :ac/charge-coin-throw}]
             (:client-actions plan)))
      (is (true? (:consume? plan))))))

(deftest level-change-event-uses-service-recalc-path-test
  (let [updated (atom nil)
        recalc-calls (atom [])]
    (with-redefs [svc-res/recalc-max-for-level (fn [rd level uuid]
                                                 (swap! recalc-calls conj [level uuid rd])
                                                 (assoc rd :recalc-level level :recalc-uuid uuid :from :svc-res))
                  ps/update-resource-data! (fn [uuid updater]
                                             (reset! updated {:uuid uuid
                                                              :resource (updater {:cur-cp 100.0 :max-cp 200.0})})
                                             nil)]
      (server-hooks/register-lifecycle-subscriptions!)
      (evt/fire-ability-event! (evt/make-level-change-event "u-level" 2 3))
      (is (= [[3 "u-level" {:cur-cp 100.0 :max-cp 200.0}]] @recalc-calls))
      (is (= {:uuid "u-level"
              :resource {:cur-cp 100.0
                         :max-cp 200.0
                         :recalc-level 3
                         :recalc-uuid "u-level"
                         :from :svc-res}}
             @updated)))))

(deftest overload-event-aborts-player-contexts-test
  (let [aborted (atom [])]
    (with-redefs [ctx-mgr/abort-player-contexts!
                  (fn [uuid]
                    (swap! aborted conj uuid)
                    nil)]
      (server-hooks/register-lifecycle-subscriptions!)
      (evt/fire-ability-event! (evt/make-overload-event "u-overload"))
      (is (= ["u-overload"] @aborted)))))

      (deftest category-change-event-aborts-deactivates-and-recalculates-test
        (let [aborted (atom [])
         updates (atom [])
         recalc-calls (atom [])]
          (with-redefs [ctx-mgr/abort-player-contexts!
              (fn [uuid]
                (swap! aborted conj uuid)
                nil)
              ps/get-player-state (fn [_]
                     {:ability-data {:level 4}})
                  ps/update-resource-data! (fn [uuid updater & args]
                                             (swap! updates conj [uuid (apply updater {:activated true
                                                                                       :cur-cp 10.0
                                                                                       :max-cp 20.0}
                                                                          args)])
                     nil)
              svc-res/recalc-max-for-level (fn [rd level uuid]
                    (swap! recalc-calls conj [level uuid rd])
                    (assoc rd :recalc-level level))]
            (server-hooks/register-lifecycle-subscriptions!)
            (evt/fire-ability-event! (evt/make-category-change-event "u-category" :old :new))
            (is (= ["u-category"] @aborted))
            (is (= [4 "u-category" {:activated true :cur-cp 10.0 :max-cp 20.0}]
              (first @recalc-calls)))
            (is (= ["u-category" false]
              [(ffirst @updates) (get-in (first @updates) [1 :activated])]))
            (is (= ["u-category" 4]
              [(first (second @updates)) (get-in (second @updates) [1 :recalc-level])])))))
(ns cn.li.ac.ability.adapters.server-hooks-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cn.li.ac.ability.adapters.server-hooks :as server-hooks]
            [cn.li.ac.ability.item-actions :as item-actions]
            [cn.li.ac.ability.registry.event :as evt]
            [cn.li.ac.ability.server.service.resource :as svc-res]
            [cn.li.ac.ability.service.player-state :as ps]))

(defn- reset-registries! [f]
  (let [item-action-registry-val @#'cn.li.ac.ability.item-actions/item-action-registry
        action-handlers-val @#'cn.li.ac.ability.item-actions/action-handlers
        item-entity-spawns-val @#'cn.li.ac.ability.item-actions/item-entity-spawns]
    (try
      (item-actions/reset-item-action-registries!)
      (f)
      (finally
        (reset! @#'cn.li.ac.ability.item-actions/item-action-registry item-action-registry-val)
        (reset! @#'cn.li.ac.ability.item-actions/action-handlers action-handlers-val)
        (reset! @#'cn.li.ac.ability.item-actions/item-entity-spawns item-entity-spawns-val)))))

(defn- reset-lifecycle-subs! [f]
  (reset! @#'cn.li.ac.ability.registry.event/subscribers {})
  (reset! @#'cn.li.ac.ability.adapters.server-hooks/lifecycle-subscriptions-registered? false)
  (f)
  (reset! @#'cn.li.ac.ability.registry.event/subscribers {})
  (reset! @#'cn.li.ac.ability.adapters.server-hooks/lifecycle-subscriptions-registered? false))

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
      (is (= [{:kind :notify-local-effect}] (:client-actions plan)))
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
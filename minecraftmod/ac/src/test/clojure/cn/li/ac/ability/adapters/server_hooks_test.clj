(ns cn.li.ac.ability.adapters.server-hooks-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cn.li.ac.ability.adapters.server-hooks :as server-hooks]
            [cn.li.ac.ability.item-actions :as item-actions]))

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

(use-fixtures :each reset-registries!)

(deftest build-item-use-plan-order-test
  (testing "coin use plans consume, dispatch domain action, then spawn scripted effect"
    (item-actions/register-item-action! "ac:coin" :railgun-coin-throw)
    (item-actions/register-item-entity-spawn! "ac:coin" {:entity-id "entity_coin_throwing" :speed 0.0})
    (let [build-plan (:build-item-use-plan (server-hooks/runtime-server-hooks))
          plan (build-plan "p1" "ac:coin" true :server)]
      (is (= [{:kind :consume-item :count 1 :unless-instabuild? true}
              {:kind :domain-action :action :railgun-coin-throw :payload {}}
              {:kind :spawn-scripted-effect :entity-id "entity_coin_throwing" :speed 0.0}]
             (:server-actions plan)))
      (is (= [{:kind :notify-local-effect}] (:client-actions plan)))
      (is (true? (:consume? plan))))))
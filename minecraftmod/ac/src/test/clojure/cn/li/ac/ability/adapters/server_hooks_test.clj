(ns cn.li.ac.ability.adapters.server-hooks-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cn.li.ac.ability.adapters.server-hooks :as server-hooks]
            [cn.li.ac.ability.item-actions :as item-actions]
            [cn.li.ac.ability.registry.event :as evt]
            [cn.li.ac.ability.server.service.context-mgr :as ctx-mgr]
            [cn.li.ac.ability.server.service.delayed-projectiles :as delayed-projectiles]
            [cn.li.ac.ability.server.service.resource :as svc-res]
            [cn.li.ac.ability.service.dispatcher :as ctx]
            [cn.li.ac.ability.service.player-state :as ps]
            [cn.li.ac.wireless.data.world-registry :as world-registry]))

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
(use-fixtures :each
  (fn [f]
    (ctx/reset-contexts-for-test!)
    (try
      (f)
      (finally
        (ctx/reset-contexts-for-test!)))))

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
                                                              :resource (updater {:cur-cp 100.0
                                                                                  :max-cp 200.0
                                                                                  :add-max-cp 5.0
                                                                                  :add-max-overload 7.0})})
                                             nil)]
      (server-hooks/register-lifecycle-subscriptions!)
      (evt/fire-ability-event! (evt/make-level-change-event "u-level" 2 3))
      (is (= [[3 "u-level" {:cur-cp 100.0
                             :max-cp 200.0
                             :add-max-cp 0.0
                             :add-max-overload 0.0}]]
             @recalc-calls))
      (is (= {:uuid "u-level"
              :resource {:cur-cp 100.0
                         :max-cp 200.0
                         :add-max-cp 0.0
                         :add-max-overload 0.0
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

(deftest player-logout-clears-delayed-projectiles-test
  (let [called (atom [])
        logout! (:on-player-logout! (server-hooks/runtime-server-hooks))]
    (with-redefs [ctx-mgr/abort-player-contexts! (fn [uuid]
                                                   (swap! called conj [:abort uuid])
                                                   nil)
                  delayed-projectiles/clear-player-tasks! (fn [uuid]
                                                            (swap! called conj [:projectiles uuid])
                                                            nil)
                  ps/remove-player-state! (fn [uuid]
                                            (swap! called conj [:remove-state uuid])
                                            nil)]
      (logout! "player-1"))
    (is (= [[:abort "player-1"]
            [:projectiles "player-1"]
            [:remove-state "player-1"]]
           @called))))

(deftest on-player-tick-drives-player-contexts-before-manager-sweep-test
  (let [calls (atom [])
        tick! (:on-player-tick! (server-hooks/runtime-server-hooks))]
    (with-redefs [ps/get-or-create-player-state! (fn [uuid]
                                                   (swap! calls conj [:ensure-state uuid])
                                                   nil)
                  ps/server-tick-player! (fn [uuid payload]
                                           (swap! calls conj [:player-state-tick uuid payload])
                                           nil)
                  ctx-mgr/tick-player-contexts! (fn [uuid]
                                                 (swap! calls conj [:context-tick uuid])
                                                 nil)
                  delayed-projectiles/tick-player! (fn [uuid]
                                                     (swap! calls conj [:projectiles uuid])
                                                     nil)
                  ctx-mgr/tick-context-manager! (fn []
                                                 (swap! calls conj [:context-manager])
                                                 nil)]
      (tick! "p1")
      (is (= [[:ensure-state "p1"]
              [:player-state-tick "p1" nil]
              [:context-tick "p1"]
              [:projectiles "p1"]
              [:context-manager]]
             @calls)))))

(deftest get-context-player-uuid-requires-owner-or-unique-match-test
  (let [get-player-uuid (:get-context-player-uuid (server-hooks/runtime-server-hooks))
        session-a [:server-a "player-a"]
        session-b [:server-b "player-b"]]
    (binding [ctx/*context-owner* {:logical-side :server :session-id session-a}]
      (ctx/register-context! {:id "dup-ctx" :player-uuid "player-a" :logical-side :server :session-id session-a})
      (ctx/register-context! {:id "unique-ctx" :player-uuid "player-a" :logical-side :server :session-id session-a}))
    (binding [ctx/*context-owner* {:logical-side :server :session-id session-b}]
      (ctx/register-context! {:id "dup-ctx" :player-uuid "player-b" :logical-side :server :session-id session-b}))
    (is (= "player-a"
           (binding [ctx/*context-owner* {:logical-side :server :session-id session-a}]
             (get-player-uuid "dup-ctx"))))
    (is (= "player-a" (get-player-uuid "unique-ctx")))
    (is (nil? (get-player-uuid "dup-ctx")))))

(deftest server-stop-clears-session-state-test
  (let [called (atom [])
        stop! (:on-server-stop! (server-hooks/runtime-server-hooks))]
    (with-redefs [ctx/clear-session-contexts! (fn [session-id]
                                                (swap! called conj [:contexts session-id])
                                                nil)
                  ps/clear-session-player-states! (fn [session-id]
                                                    (swap! called conj [:player-states session-id])
                                                    nil)
          world-registry/clear-session-world-data! (fn [session-id]
                       (swap! called conj [:wireless session-id])
                       nil)
                  delayed-projectiles/clear-all-tasks! (fn []
                                                         (swap! called conj [:projectiles])
                                                         nil)]
      (stop! :server-session))
    (is (= [[:contexts :server-session]
            [:player-states :server-session]
          [:wireless :server-session]
            [:projectiles]]
           @called))))

      (deftest category-change-event-aborts-deactivates-and-recalculates-test
        (let [aborted (atom [])
        resource-updates (atom [])
        cooldown-clears (atom [])
         recalc-calls (atom [])]
          (with-redefs [ctx-mgr/abort-player-contexts!
              (fn [uuid]
                (swap! aborted conj uuid)
                nil)
              ps/get-player-state (fn [_]
                     {:ability-data {:level 4}})
            ps/update-cooldown-data! (fn [uuid updater]
                     (swap! cooldown-clears conj [uuid (updater {:existing true})])
                     nil)
                  ps/update-resource-data! (fn [uuid updater & args]
                     (swap! resource-updates conj [uuid (apply updater {:activated true
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
            (is (= [["u-category" {}]] @cooldown-clears))
            (is (= [4 "u-category" {:activated true :cur-cp 10.0 :max-cp 20.0}]
              (first @recalc-calls)))
            (is (= ["u-category" false]
                    [(ffirst @resource-updates) (get-in (first @resource-updates) [1 :activated])]))
            (is (= ["u-category" 4]
                    [(first (second @resource-updates)) (get-in (second @resource-updates) [1 :recalc-level])])))))
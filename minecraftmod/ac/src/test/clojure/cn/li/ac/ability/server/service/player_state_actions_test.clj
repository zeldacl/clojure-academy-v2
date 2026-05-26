(ns cn.li.ac.ability.server.service.player-state-actions-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.adapters.server-hooks :as server-hooks]
            [cn.li.ac.ability.model.ability :as adata]
            [cn.li.ac.ability.model.cooldown :as cdata]
            [cn.li.ac.ability.model.develop :as ddata]
            [cn.li.ac.ability.model.preset :as pdata]
            [cn.li.ac.ability.model.resource :as rdata]
            [cn.li.ac.ability.registry.event :as evt]
            [cn.li.ac.ability.registry.skill-query :as skill-query]
            [cn.li.ac.ability.server.service.context-mgr :as ctx-mgr]
            [cn.li.ac.ability.server.service.player-state-actions :as state-actions]
            [cn.li.ac.ability.server.service.resource :as svc-res]
            [cn.li.ac.ability.service.player-state :as ps]
            [cn.li.ac.test.support.player-state :as ps-fix]))

(defn- reset-lifecycle-subs! [f]
  (ps-fix/with-test-player-state-owner
   (fn []
     (evt/reset-ability-event-subscribers-for-test!)
     (server-hooks/reset-lifecycle-subscriptions-registered-for-test!)
     (try
       (f)
       (finally
         (evt/reset-ability-event-subscribers-for-test!)
         (server-hooks/reset-lifecycle-subscriptions-registered-for-test!))))))

(use-fixtures :each ps-fix/clean-player-states-fixture)
(use-fixtures :each reset-lifecycle-subs!)

(deftest set-level-fires-recalc-with-reset-add-max-test
  (let [recalc-calls (atom [])]
    (ps/set-player-state! "p1"
                          (-> (ps/fresh-state)
                              (assoc-in [:ability-data :level] 2)
                              (assoc-in [:resource-data :add-max-cp] 5.0)
                              (assoc-in [:resource-data :add-max-overload] 7.0)
                              (assoc-in [:resource-data :cur-cp] 80.0)
                              (assoc-in [:resource-data :max-cp] 120.0)
                              (assoc-in [:resource-data :max-overload] 40.0)))
    (with-redefs [svc-res/recalc-max-for-level (fn [rd level uuid]
                                                 (swap! recalc-calls conj [level uuid rd])
                                                 (assoc rd :recalc-level level))]
      (server-hooks/register-lifecycle-subscriptions!)
      (state-actions/set-level! "p1" 3)
      (is (= 3 (get-in (ps/get-player-state "p1") [:ability-data :level])))
      (is (= [3 "p1" {:cur-cp 80.0
                       :max-cp 120.0
                       :max-overload 40.0
                       :add-max-cp 0.0
                       :add-max-overload 0.0}]
             (let [[level uuid rd] (first @recalc-calls)]
               [level uuid (select-keys rd [:cur-cp :max-cp :max-overload :add-max-cp :add-max-overload])]))))))

(deftest recover-all-restores-cp-overload-and-recovery-timers-test
  (ps/set-player-state! "p1"
                        (-> (ps/fresh-state)
                            (assoc :resource-data {:cur-cp 5.0
                                                   :max-cp 42.0
                                                   :cur-overload 17.0
                                                   :max-overload 80.0
                                                   :add-max-cp 1.0
                                                   :add-max-overload 2.0
                                                   :activated true
                                                   :overload-fine false
                                                   :until-recover 9
                                                   :until-overload-recover 11
                                                   :interferences #{:jam}})))
  (state-actions/recover-all! "p1")
  (is (= {:cur-cp 42.0
          :max-cp 42.0
          :cur-overload 0.0
          :max-overload 80.0
          :add-max-cp 1.0
          :add-max-overload 2.0
          :activated true
          :overload-fine true
          :until-recover 0
          :until-overload-recover 0
          :interferences #{:jam}}
         (:resource-data (ps/get-player-state "p1")))))

(deftest unlearn-skill-clears-skill-exp-and-preset-slot-test
  (ps/set-player-state! "p1"
                        (-> (ps/fresh-state)
                            (assoc-in [:ability-data :learned-skills] #{:railgun :arc-gen})
                            (assoc-in [:ability-data :skill-exps] {:railgun 0.8 :arc-gen 0.2})
                            (assoc-in [:preset-data :slots] {[0 0] [:electromaster :railgun]
                                                             [0 1] [:electromaster :arc-gen]})))
  (with-redefs [skill-query/controllable-key (fn [skill-id]
                                               (case skill-id
                                                 :railgun [:electromaster :railgun]
                                                 :arc-gen [:electromaster :arc-gen]
                                                 nil))]
    (state-actions/unlearn-skill! "p1" :railgun))
  (let [state (ps/get-player-state "p1")]
    (is (= #{:arc-gen} (get-in state [:ability-data :learned-skills])))
    (is (= {:arc-gen 0.2} (get-in state [:ability-data :skill-exps])))
    (is (nil? (get-in state [:preset-data :slots [0 0]])))
    (is (= [:electromaster :arc-gen] (get-in state [:preset-data :slots [0 1]])))))

(deftest reset-abilities-resets-runtime-slices-and-fires-category-side-effects-test
  (let [aborted (atom [])]
    (ps/set-player-state! "p1"
                          (-> (ps/fresh-state)
                              (assoc :cheats-enabled? true)
                              (assoc-in [:ability-data :category-id] :electromaster)
                              (assoc-in [:ability-data :level] 4)
                              (assoc :cooldown-data {[:railgun :main] 20})
                              (assoc-in [:preset-data :slots] {[0 0] [:electromaster :railgun]})
                              (assoc-in [:resource-data :activated] true)
                              (assoc-in [:develop-data :state] :developing)))
    (with-redefs [ctx-mgr/abort-player-contexts! (fn [uuid]
                                                   (swap! aborted conj uuid)
                                                   nil)
                  svc-res/recalc-max-for-level (fn [rd _level _uuid] rd)]
      (server-hooks/register-lifecycle-subscriptions!)
      (state-actions/reset-abilities! "p1")
      (let [state (ps/get-player-state "p1")]
        (is (= ["p1"] @aborted))
        (is (= (adata/new-ability-data) (:ability-data state)))
        (is (= (cdata/new-cooldown-data) (:cooldown-data state)))
        (is (= (pdata/new-preset-data) (:preset-data state)))
        (is (= (ddata/new-develop-data) (:develop-data state)))
        (is (= true (:cheats-enabled? state)))
        (is (= (rdata/new-resource-data) (:resource-data state)))))))
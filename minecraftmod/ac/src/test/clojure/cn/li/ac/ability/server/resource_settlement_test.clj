(ns cn.li.ac.ability.server.resource-settlement-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.config :as cfg]
            [cn.li.ac.ability.model.resource :as rdata]
            [cn.li.ac.ability.registry.event :as evt]
            [cn.li.ac.ability.server.service.resource :as resource]))

(defn- stable-resource-config-redefs
  [f]
  (with-redefs [cfg/cp-recover-cooldown (constantly 3)
                cfg/overload-recover-cooldown (constantly 4)
                cfg/maxcp-incr-rate (constantly 0.1)
                cfg/maxo-incr-rate (constantly 0.2)
                cfg/max-cp-for-level (fn [_] 100.0)
                cfg/max-overload-for-level (fn [_] 10.0)
                cfg/add-cp-ceiling (fn [_] 1000.0)
                cfg/add-overload-ceiling (fn [_] 1000.0)
                cfg/max-overload-growth-per-event (constantly 10.0)]
    (f)))

(deftest perform-resource-success-consumes-grows-recalculates-and-emits-overload-test
  (stable-resource-config-redefs
   (fn []
     (let [calc-calls (atom [])
           res-data (assoc (rdata/new-resource-data 100.0 10.0)
                           :activated true
                           :cur-cp 100.0
                           :cur-overload 9.0
                           :overload-fine true)]
       (with-redefs [evt/fire-calc-event! (fn [event-type base-value extra]
                                            (swap! calc-calls conj [event-type base-value extra])
                                            base-value)]
         (let [{:keys [data success? events]} (resource/perform-resource res-data "p1" 2.0 10.0 false 1)]
           (is (true? success?))
           (is (= 90.0 (:cur-cp data)))
           (is (= 10.0 (:cur-overload data)))
           (is (= 101.0 (:max-cp data)))
           (is (= 10.4 (:max-overload data)))
           (is (= 1.0 (:add-max-cp data)))
           (is (= 0.4 (:add-max-overload data)))
           (is (= [{:event/type evt/EVT-OVERLOAD :event/side :server :uuid "p1"}]
                  events))
           (is (= [[evt/CALC-SKILL-PERFORM 10.0 {:field :cp :uuid "p1"}]
                   [evt/CALC-SKILL-PERFORM 2.0 {:field :overload :uuid "p1"}]
                   [evt/CALC-MAX-CP 101.0 {:uuid "p1"}]
                   [evt/CALC-MAX-OVERLOAD 10.4 {:uuid "p1"}]]
                  @calc-calls))))))))

(deftest perform-resource-failure-does-not-calc-or-mutate-test
  (stable-resource-config-redefs
   (fn []
     (let [calc-calls (atom 0)
           res-data (assoc (rdata/new-resource-data 100.0 10.0)
                           :activated false
                           :cur-cp 100.0
                           :cur-overload 0.0)]
       (with-redefs [evt/fire-calc-event! (fn [& _]
                                            (swap! calc-calls inc)
                                            999.0)]
         (is (= {:data res-data :success? false :events []}
                (resource/perform-resource res-data "p1" 2.0 10.0 false 1)))
          (is (= 0 @calc-calls)))))))

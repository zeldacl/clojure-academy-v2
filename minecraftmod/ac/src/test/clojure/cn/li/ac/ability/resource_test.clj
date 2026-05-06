(ns cn.li.ac.ability.resource-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.ability.model.resource :as rdata]
            [cn.li.ac.ability.registry.event :as evt]
            [cn.li.ac.ability.server.service.resource :as res]))

(deftest set-activated-event-test
  (let [base (rdata/new-resource-data)]
    (with-redefs [evt/make-activate-event (fn [uuid] {:event/type :ability/activate :uuid uuid})
                  evt/make-deactivate-event (fn [uuid] {:event/type :ability/deactivate :uuid uuid})]
      (let [{:keys [data events]} (res/set-activated base "u1" true)]
        (is (true? (:activated data)))
        (is (= [{:event/type :ability/activate :uuid "u1"}] events)))
      (let [{:keys [events]} (res/set-activated (assoc base :activated true) "u1" true)]
        (is (empty? events)))
      (let [{:keys [events]} (res/set-activated (assoc base :activated true) "u1" false)]
        (is (= [{:event/type :ability/deactivate :uuid "u1"}] events))))))

(deftest recalc-max-for-level-applies-calc-events-test
  (let [base {:cur-cp 80.0
              :max-cp 100.0
              :cur-overload 30.0
              :max-overload 40.0
              :add-max-cp 0.0
              :add-max-overload 0.0
              :activated true
              :overload-fine true
              :until-recover 0
              :until-overload-recover 0
              :interferences #{}}]
    (with-redefs [rdata/recalc-max-values (fn [d _]
                                            (assoc d :max-cp 120.0 :max-overload 60.0 :cur-cp 120.0 :cur-overload 50.0))
                  evt/fire-calc-event! (fn [event-type v _]
                                         (case event-type
                                           :calc/max-cp (+ v 10.0)
                                           :calc/max-overload (+ v 5.0)
                                           v))]
      (let [ret (res/recalc-max-for-level base 3 "u2")]
        (is (= 130.0 (:max-cp ret)))
        (is (= 65.0 (:max-overload ret)))
        (is (= 120.0 (:cur-cp ret)))
        (is (= 50.0 (:cur-overload ret)))))))

(deftest perform-resource-success-failure-and-overload-event-test
  (let [base {:cur-cp 100.0
              :max-cp 200.0
              :cur-overload 10.0
              :max-overload 30.0
              :add-max-cp 0.0
              :add-max-overload 0.0
              :activated true
              :overload-fine true
              :until-recover 0
              :until-overload-recover 0
              :interferences #{}}]
    (with-redefs [rdata/can-perform? (fn [_ _ _ _] false)]
      (let [{:keys [success? data events]} (res/perform-resource base "u3" 1.0 1.0 false 1)]
        (is (false? success?))
        (is (= base data))
        (is (empty? events))))
    (with-redefs [rdata/can-perform? (fn [_ _ _ _] true)
                  evt/fire-calc-event! (fn [event-type v _]
                                         (if (= event-type :calc/skill-perform) v v))
                  rdata/consume-cp (fn [d cp _] (update d :cur-cp - cp))
                  rdata/add-overload (fn [d overload _]
                                       [(assoc d :cur-overload (+ (:cur-overload d) overload)) true])
                  rdata/grow-max-cp (fn [d _ _ _] (assoc d :add-max-cp 2.5))
                  rdata/grow-max-overload (fn [d _ _ _] (assoc d :add-max-overload 1.0))
                  res/recalc-max-for-level (fn [d _ _] (assoc d :max-cp 205.0 :max-overload 35.0))
                  evt/make-overload-event (fn [uuid] {:event/type :ability/overload :uuid uuid})]
      (let [{:keys [success? data events]} (res/perform-resource base "u3" 25.0 10.0 false 2)]
        (is success?)
        (is (= 90.0 (:cur-cp data)))
        (is (= 205.0 (:max-cp data)))
        (is (= [{:event/type :ability/overload :uuid "u3"}] events))))))

(deftest server-tick-recovery-eventless-test
  (with-redefs [evt/fire-calc-event! (fn [event-type _ _]
                                       (case event-type
                                         :calc/cp-recover-speed 2.0
                                         :calc/overload-recover-speed 3.0
                                         1.0))
                rdata/tick-cp-recovery (fn [d speed] (assoc d :cp-speed speed))
                rdata/tick-overload-recovery (fn [d speed] (assoc d :ol-speed speed))]
    (let [{:keys [data events]} (res/server-tick (rdata/new-resource-data) "u4")]
      (is (= 2.0 (:cp-speed data)))
      (is (= 3.0 (:ol-speed data)))
      (is (empty? events)))))

(deftest set-activated-public-boundary-no-stub-test
  (let [base (rdata/new-resource-data)
        {:keys [data]} (res/set-activated base "u-public" true)]
    (is (true? (:activated data)))))

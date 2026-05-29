(ns cn.li.ac.ability.service.reducer-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.model.resource :as rdata]
            [cn.li.ac.ability.registry.event :as evt]
            [cn.li.ac.ability.service.reducer :as reducer]
            [cn.li.ac.ability.service.runtime-store :as runtime-store]))

(defn- base-state []
  (runtime-store/fresh-player-state))

(deftest set-activated-command-emits-event-test
  (let [player-state (base-state)
        result (reducer/apply-command player-state
                                      {:command :set-activated
                                       :player-uuid "p-1"
                                       :activated true})]
    (is (true? (get-in result [:state :resource-data :activated])))
    (is (= [evt/EVT-ABILITY-ACTIVATE]
           (mapv :event/type (:events result))))
    (is (empty? (:effects result)))))

(deftest consume-resource-command-updates-cp-when-activated-test
  (let [player-state (-> (base-state)
                         (assoc :resource-data (rdata/set-activated (:resource-data (base-state)) true)))
        result (reducer/apply-command player-state
                                      {:command :consume-resource
                                       :player-uuid "p-2"
                                       :cp 10.0
                                       :overload 3.0
                                       :creative? false})]
    (is (true? (:success? result)))
    (is (< (get-in result [:state :resource-data :cur-cp])
           (get-in player-state [:resource-data :cur-cp])))))

(deftest apply-commands-accumulates-events-and-effects-test
  (let [player-state (base-state)
        result (reducer/apply-commands player-state
                                       [{:command :set-activated
                                         :player-uuid "p-3"
                                         :activated true}
                                        {:command :switch-preset
                                         :player-uuid "p-3"
                                         :preset-idx 2}])]
    (is (= 2 (count (:events result))))
    (is (= 1 (count (:effects result))))
    (is (= :network-send (-> result :effects first :effect/type)))
    (is (= 2 (get-in result [:state :preset-data :active-preset])))))

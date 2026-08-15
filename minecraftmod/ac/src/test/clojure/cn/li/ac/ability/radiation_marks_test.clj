(ns cn.li.ac.ability.radiation-marks-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.service.radiation-marks :as marks]))

(deftest mark-refreshes-without-shortening-existing-lifetime
  (let [first-mark (marks/mark nil {:source-player-id "source"
                                    :target-id "target"
                                    :rate 1.4
                                    :duration 60
                                    :tick 10})
        refreshed (marks/mark first-mark {:source-player-id "source"
                                          :target-id "target"
                                          :rate 1.8
                                          :duration 60
                                          :tick 11})]
    (is (= 60 (:ticks-left first-mark)))
    (is (= 60 (:ticks-left refreshed)))
    (is (= 1.8 (:rate refreshed)))))

(deftest tick-expires-and-owner-cleanup-is-deterministic
  (let [state {"target" {:source-player-id "source" :ticks-left 1}
               "other" {:source-player-id "other" :ticks-left 4}}]
    (is (= {"other" {:source-player-id "other"
                      :ticks-left 3
                      :updated-at-tick 20}}
           (marks/tick state 20)))
    (is (= {"other" {:source-player-id "other" :ticks-left 4}}
           (marks/clear-owner state "source")))))


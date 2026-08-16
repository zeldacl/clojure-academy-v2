(ns cn.li.platform.neutral.presentation-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.platform.neutral.presentation :as presentation]))

(deftest coalesce-frame-id-groups-calls-within-one-real-frame
  (testing "a second call microseconds later stays on the same frame id"
    (let [[same? next-nanos] (presentation/coalesce-frame-id 1000500 1000000)]
      (is (true? same?))
      (is (= 1000000 next-nanos))))
  (testing "a call past the coalesce window starts a new frame id"
    (let [[same? next-nanos] (presentation/coalesce-frame-id
                                (+ 1000000 presentation/frame-coalesce-window-nanos 1)
                                1000000)]
      (is (false? same?))
      (is (= (+ 1000000 presentation/frame-coalesce-window-nanos 1) next-nanos))))
  (testing "exactly at the window boundary is a new frame (half-open window)"
    (let [[same? _] (presentation/coalesce-frame-id
                       (+ 1000000 presentation/frame-coalesce-window-nanos)
                       1000000)]
      (is (false? same?)))))

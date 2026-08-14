(ns cn.li.presentation.devtools.perf-test
  (:require [clojure.test :refer :all]
            [cn.li.presentation.devtools.perf :as perf]))

(deftest rolling-summary-is-bounded
  (let [panel (perf/create 2)]
    (perf/record! panel {:cpu-ms 1.0 :draw-calls 4 :dirty #{:paint}})
    (perf/record! panel {:cpu-ms 2.0 :draw-calls 8 :dirty #{:transform}})
    (perf/record! panel {:cpu-ms 10.0 :draw-calls 16 :dirty #{:layout}})
    (let [summary (perf/summary panel)]
      (is (= 2 (:frames summary)))
      (is (= 10.0 (:cpu-p95-ms summary)))
      (is (= 16.0 (:draw-calls-p95 summary))))))

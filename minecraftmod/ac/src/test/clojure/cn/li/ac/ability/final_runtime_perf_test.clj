(ns cn.li.ac.ability.final-runtime-perf-test
  "Opt-in headless scheduler benchmark for JFR collection.

   This namespace is intentionally not part of the default AC test run. Invoke
   it with -Dac.test.only=cn.li.ac.ability.final-runtime-perf-test and keep
   the resulting JFR outside the repository's source tree."
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.final-runtime :as runtime]))

(def ^:private program
  {:schema-version 1
   :program {:component :flow/sequence
             :kind :flow
             :steps [{:component :flow/finish :outcome :perf}]}
   :instructions 1})

(def ^:private frame
  {:owner "perf-owner" :world "perf-world" :ability-id :perf/ability
   :tick 0 :seed 1 :input {}})

(defn- benchmark-runtime []
  {:apis {:execute (fn [_ _ frame]
                     {:status :accepted :outcome (:tick frame)})}
   :engine nil
   :scheduled (atom (sorted-map))})

(deftest scheduler-hotpath-jfr-benchmark-test
  (let [rt (benchmark-runtime)
        iterations 200000
        started (System/nanoTime)]
    (dotimes [tick iterations]
      (reset! (:scheduled rt)
              (sorted-map tick [{:tick tick :program program :frame frame}]))
      (runtime/tick! rt tick))
    (let [elapsed-ms (/ (- (System/nanoTime) started) 1000000.0)]
      (println (format "[final-runtime-perf] iterations=%d elapsed-ms=%.3f ops-per-second=%.1f"
                       iterations elapsed-ms (/ (* iterations 1000.0) elapsed-ms)))
      (is (empty? @(:scheduled rt))))))

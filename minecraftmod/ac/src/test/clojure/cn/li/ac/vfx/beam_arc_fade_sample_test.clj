(ns cn.li.ac.vfx.beam-arc-fade-sample-test
  "Regression: arc-gen's beam-arc-fade payload uses :ring-radius {:from :to}.
   Sampling past fade-at must lerp that map to a double before :ring."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [cn.li.vfx.scene :as scene]))

(defn- load-beam-arc-fade []
  (let [url (io/resource "ac/vfx-v4/beam-arc-fade.edn")]
    (when-not url
      (throw (ex-info "beam-arc-fade.edn missing from classpath" {})))
    (edn/read-string (slurp url))))

(deftest beam-arc-fade-samples-with-ring-radius-range-map
  (let [program (scene/compile-v4-document! (load-beam-arc-fade))
        caps {:start {:x 0.0 :y 1.0 :z 0.0}
              :end {:x 0.0 :y 1.0 :z 5.0}
              :beam-at 0
              :arc-at 0
              :fade-at 3
              :fade-from-tick 3
              :fade-to-tick 10
              :fade-from-alpha 1.0
              :fade-to-alpha 0.0
              :grow-ticks 0
              :layers [{:shape :tube :radius 0.08 :color [236 170 93 60]}]
              :ring-radius {:from 0.12 :to 0.28}
              :ring-segments 10
              :ring-color [188 252 238 180]
              :arc-pattern :weak
              :seed 42
              :age 5.0
              :progress 0.5}]
    (testing "fade phase draws ring without ClassCast on ring-radius map"
      (let [ops (scene/sample! program {:capabilities caps})
            ring (first (filter #(= :ring (:kind %)) ops))
            arc (first (filter #(= :arc (:kind %)) ops))]
        (is (some? ring))
        (is (number? (:radius ring)))
        (is (< 0.12 (double (:radius ring)) 0.28))
        (is (some? arc) "main-parity zigzag arc leaf must sample")
        (is (= :weak (:pattern arc)))))))

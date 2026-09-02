(ns cn.li.node.kernel-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.node.kernel :as kernel]))

;; A resolver exercising every opt defresolver supports, mirroring the shape
;; combat-core's resolve-value and vfx-core's graph-value are each generated
;; with -- :frame/:local/:state scopes, seed advanced via a mutable atom
;; (combat's convention), :set recursion, and :lerp? interpolation (vfx's
;; convention), all in one resolver so both behaviors get exercised.
(kernel/defresolver test-resolve ctx
  {:scopes {:frame (:frame ctx) :local (:locals ctx) :state (:state ctx)}
   :local :local
   :seed (swap! (:seed* ctx) inc)
   :extras (:extra-ops ctx)
   :coll #{:map :vector :set}
   :lerp? true})

(deftest resolves-local-ref-test
  (is (= 5.0 (test-resolve {:ref [:local :x]} {:locals {:x 5.0}}))))

(deftest resolves-local-ref-with-path-test
  (is (= 3.0 (test-resolve {:ref [:local :hit :position :y]}
                           {:locals {:hit {:position {:y 3.0}}}}))))

(deftest resolves-non-local-scope-with-get-in-path-test
  (is (= 7.0 (test-resolve {:ref [:state :owner :hp]} {:state {:owner {:hp 7.0}}}))))

(deftest resolves-nested-expr-with-advancing-seed-test
  (is (= 7.0 (test-resolve {:expr :math/add :args [{:ref [:local :a]} 2.0]}
                           {:locals {:a 5.0} :seed* (atom 0)}))))

(deftest resolves-plain-map-recursively-test
  (is (= {:x 5.0 :y 2.0}
         (test-resolve {:x {:ref [:local :a]} :y 2.0} {:locals {:a 5.0}}))))

(deftest resolves-vector-recursively-test
  (is (= [5.0 2.0] (test-resolve [{:ref [:local :a]} 2.0] {:locals {:a 5.0}}))))

(deftest resolves-set-recursively-test
  (is (= #{5.0 2.0} (test-resolve #{{:ref [:local :a]} 2.0} {:locals {:a 5.0}}))))

(deftest literal-passes-through-test
  (is (= "beam" (test-resolve "beam" {}))))

(deftest lerp-interpolates-at-progress-test
  (is (= 5.0 (test-resolve {:from 0.0 :to 10.0} {:progress 0.5})))
  (is (= 0.0 (test-resolve {:from 0.0 :to 10.0} {})))
  (is (= 10.0 (test-resolve {:from 0.0 :to 10.0} {:progress 1.0}))))

(deftest extra-ops-reachable-through-expr-evaluate-test
  (is (= 9.0 (test-resolve {:expr :test/double :args [{:ref [:local :a]}]}
                           {:locals {:a 4.5} :seed* (atom 0)
                            :extra-ops {:test/double (fn [args _seed] (* 2.0 (double (first args))))}}))))

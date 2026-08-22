(ns cn.li.vfx.v3-primitives-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.node.descriptor :as node]
            [cn.li.node.runtime :as runtime]
            [cn.li.vfx.ops :as ops]
            [cn.li.vfx.v3-primitives :as v3])
  (:import [cn.li.mcmod.math V3]))

(use-fixtures :each
  (fn [f]
    (node/reset-for-test!)
    (v3/install!)
    (f)
    (node/reset-for-test!)))

(deftest both-render-primitives-registered-test
  (doseq [id [:vfx/line :vfx/quad]]
    (let [d (node/descriptor id)]
      (is (some? d))
      (is (= :primitive (:layer d)))
      (is (fn? (:impl d))))))

(deftest line-impl-matches-v2-op-shape-test
  (let [result (runtime/invoke-primitive!
                :vfx/line {:from {:x 0.0 :y 0.0 :z 0.0} :to {:x 1.0 :y 2.0 :z 3.0} :color [1 2 3 4]}
                {})
        op (:op result)]
    (is (= :line (:kind op)))
    (is (instance? V3 (:p1 op)))
    (is (= 3.0 (.-z ^V3 (:p2 op))))
    (is (= [1 2 3 4] (:color op)))))

(deftest quad-impl-defaults-uv-test
  (let [result (runtime/invoke-primitive!
                :vfx/quad {:p0 {:x 0.0 :y 0.0 :z 0.0} :p1 {:x 1.0 :y 0.0 :z 0.0}
                          :p2 {:x 1.0 :y 1.0 :z 0.0} :p3 {:x 0.0 :y 1.0 :z 0.0}
                          :color [255 255 255 255]}
                {})
        op (:op result)]
    (is (= :quad (:kind op)))
    (is (= 0.0 (:u0 op)))
    (is (= 1.0 (:u1 op)))))

(deftest ops-batch-wraps-multiple-ops-into-single-mesh-payload-test
  (let [line-op (:op (runtime/invoke-primitive!
                      :vfx/line {:from {:x 0.0 :y 0.0 :z 0.0} :to {:x 1.0 :y 0.0 :z 0.0} :color [1 1 1 1]} {}))
        batch (ops/ops-batch :world-after-translucent [line-op])]
    (is (= :mesh (:primitive batch)))
    (is (= :ops (:variant batch)))
    (is (= [{:ops [line-op]}] (:payload batch)))))

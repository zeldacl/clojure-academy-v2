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

(deftest every-v3-primitive-registered-test
  (doseq [id [:vfx/line :vfx/quad :vfx/camera :vfx/audio-one-shot :vfx/audio-loop]]
    (let [d (node/descriptor id)]
      (is (some? d) (str id " must be registered"))
      (is (= :primitive (:layer d)))
      (is (fn? (:impl d))))))

(deftest camera-impl-tags-op-kind-test
  (let [result (runtime/invoke-primitive!
                :vfx/camera {:operation :fov :value 10.0 :duration-ticks 5} {})]
    (is (= :camera (get-in result [:op :kind])))
    (is (= :fov (get-in result [:op :operation])))))

(deftest audio-one-shot-impl-tags-op-kind-test
  (let [result (runtime/invoke-primitive!
                :vfx/audio-one-shot {:sound-id :bang :position {:x 0.0 :y 0.0 :z 0.0}} {})]
    (is (= :audio (get-in result [:op :kind])))
    (is (= :bang (get-in result [:op :sound-id])))))

(deftest audio-loop-impl-tags-op-kind-test
  (let [result (runtime/invoke-primitive!
                :vfx/audio-loop {:sound-id :hum :position {:x 0.0 :y 0.0 :z 0.0} :instance-key [:owner-1]} {})]
    (is (= :audio-loop (get-in result [:op :kind])))
    (is (= [:owner-1] (get-in result [:op :instance-key])))))

(deftest line-impl-matches-v2-op-shape-test
  (let [result (runtime/invoke-primitive!
                :vfx/line {:from {:x 0.0 :y 0.0 :z 0.0} :to {:x 1.0 :y 2.0 :z 3.0} :color [1 2 3 4]}
                {})
        op (:op result)]
    (is (= :line (:kind op)))
    (is (instance? V3 (:p1 op)))
    (is (= 3.0 (.-z ^V3 (:p2 op))))
    (is (= [1 2 3 4] (:color op)))))

(deftest line-impl-accepts-the-real-combat-vec3-literal-shape-test
  ;; Regression: cn.li.combat.vm/vec3-components (and node-core's own
  ;; :vec3/* expr ops) represent a point as {:vec3 [x y z]}, not {:x :y :z}
  ;; -- that is the ONLY shape a real ability's :effect/vfx position payload
  ;; ever carries. ops/->v3 previously only recognized {:x :y :z} and
  ;; positional [x y z]; a {:vec3 [...]} point fell through to (nth point 0
  ;; 0.0), and since a map is seqable, nth returned the single MapEntry
  ;; [:vec3 [x y z]] itself instead of a number, and (double ...) on that
  ;; threw ClassCastException. Every real ability would have crashed the
  ;; instant it called :vfx/line/:vfx/quad with a real position.
  (let [result (runtime/invoke-primitive!
                :vfx/line {:from {:vec3 [0.0 64.0 0.0]} :to {:vec3 [1.0 65.0 2.0]} :color [1 1 1 1]}
                {})
        op (:op result)]
    (is (= :line (:kind op)))
    (is (= 64.0 (.-y ^V3 (:p1 op))))
    (is (= 2.0 (.-z ^V3 (:p2 op))))))

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

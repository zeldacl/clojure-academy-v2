(ns cn.li.vfx.composite-loader-test
  "Proves vfx-core's thin cn.li.mcmod.runtime.safe-edn binding works end to
   end against a real classpath resource -- the composite mechanics
   themselves (composite expansion into :vfx/repeat + :vfx/line, the
   :vfx/ring-point expr opcode) are already thoroughly covered by
   cn.li.vfx.vm-test's mid-layer-composite-expands-and-samples-real-
   geometry-test using a Clojure-registered fixture; this test is
   specifically about the EDN-from-a-real-file path replacing what used to
   be a composite hardcoded as a Clojure map (see this namespace's own
   docstring in composite_loader.clj)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.node.descriptor :as node]
            [cn.li.vfx.v3-primitives :as v3-primitives]
            [cn.li.vfx.composite-loader :as composite-loader]
            [cn.li.vfx.vm :as vm])
  (:import [cn.li.mcmod.math V3]))

(use-fixtures :each
  (fn [f]
    (node/reset-for-test!)
    (v3-primitives/install!)
    (f)
    (node/reset-for-test!)))

(defn- collecting-sink []
  (let [batches (atom [])]
    {:sink {:emit! (fn [batch] (swap! batches conj batch) batch)}
     :batches batches}))

(deftest install-loads-a-real-edn-resource-via-safe-edn-test
  (let [result (composite-loader/install! "cn/li/vfx/composites_test/manifest.edn")]
    (is (= [:test/ring] (:registered result)))
    (is (empty? (:errors result)))
    (is (= :mid (:layer (node/descriptor :test/ring))))))

(deftest a-loaded-composite-actually-samples-real-geometry-test
  (composite-loader/install! "cn/li/vfx/composites_test/manifest.edn")
  (let [{:keys [sink batches]} (collecting-sink)
        call {:component :test/ring :center {:x 0.0 :y 0.0 :z 0.0} :radius 3.0 :segments 6 :color [1 1 1 1]}]
    (vm/sample-node! call {:input {} :seed 0 :sink sink})
    (is (= 6 (count @batches)))
    (let [op (first (:ops (first (:payload (first @batches)))))]
      (is (< (Math/abs (- 3.0 (Math/sqrt (+ (Math/pow (.-x ^V3 (:p1 op)) 2) (Math/pow (.-z ^V3 (:p1 op)) 2)))))
             1.0e-9)))))

(ns cn.li.vfx.v3-composites-test
  "End-to-end proof for R3 step 5 (mossy-wren plan): a real EDN-shaped
   :layer :mid composite, registered on node-core, expands through
   cn.li.vfx.vm's composite-expansion fallback and samples real,
   renderable :vfx/line ops -- the first version of :vfx/charge-ring's
   effect that can actually draw anything (see v3_composites.clj's own
   docstring for why this is not a golden-op equivalence test)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.node.descriptor :as node]
            [cn.li.vfx.v3-primitives :as v3-primitives]
            [cn.li.vfx.v3-composites :as v3-composites]
            [cn.li.vfx.vm :as vm])
  (:import [cn.li.mcmod.math V3]))

(use-fixtures :each
  (fn [f]
    (node/reset-for-test!)
    (v3-primitives/install!)
    (v3-composites/install!)
    (f)
    (node/reset-for-test!)))

(defn- collecting-sink []
  (let [batches (atom [])]
    {:sink {:emit! (fn [batch] (swap! batches conj batch) batch)}
     :batches batches}))

(deftest charge-ring-registers-as-mid-composite-test
  (let [d (node/descriptor :vfx.fx/charge-ring)]
    (is (some? d))
    (is (= :mid (:layer d)))
    (is (nil? (:impl d)))))

(deftest charge-ring-emits-one-line-per-segment-forming-a-closed-loop-test
  (let [{:keys [sink batches]} (collecting-sink)
        call {:component :vfx.fx/charge-ring
              :center {:x 0.0 :y 10.0 :z 0.0}
              :charge-ticks 0.0 :max-charge-ticks 100.0
              :segments 8 :base-radius 2.0 :radius-growth 3.0
              :pulse-amplitude 0.0 :pulse-frequency 0.0
              :color [255 200 100 255]}]
    (vm/sample-node! call {:input {} :seed 0 :sink sink})
    (is (= 8 (count @batches)))
    (let [op0 (first (:ops (first (:payload (first @batches)))))
          op7 (first (:ops (first (:payload (last @batches)))))]
      ;; at 0 charge (progress 0, no pulse), radius is exactly :base-radius
      (is (< (Math/abs (- 2.0 (Math/sqrt (+ (Math/pow (.-x ^V3 (:p1 op0)) 2)
                                             (Math/pow (.-z ^V3 (:p1 op0)) 2)))))
             1.0e-9))
      ;; segment 7's :to closes the loop back to segment 0's :from
      (is (< (Math/abs (- (.-x ^V3 (:p1 op0)) (.-x ^V3 (:p2 op7)))) 1.0e-9))
      (is (< (Math/abs (- (.-z ^V3 (:p1 op0)) (.-z ^V3 (:p2 op7)))) 1.0e-9)))))

(deftest charge-ring-radius-grows-with-progress-test
  (let [{:keys [sink batches]} (collecting-sink)
        radius-at (fn [charge-ticks]
                    (reset! batches [])
                    (vm/sample-node! {:component :vfx.fx/charge-ring
                                      :center {:x 0.0 :y 0.0 :z 0.0}
                                      :charge-ticks charge-ticks :max-charge-ticks 100.0
                                      :segments 4 :base-radius 1.0 :radius-growth 4.0
                                      :pulse-amplitude 0.0 :pulse-frequency 0.0
                                      :color [1 1 1 1]}
                                     {:input {} :seed 0 :sink sink})
                    (let [p (:p1 (first (:ops (first (:payload (first @batches))))))]
                      (Math/sqrt (+ (Math/pow (.-x ^V3 p) 2) (Math/pow (.-z ^V3 p) 2)))))]
    (is (< (Math/abs (- 1.0 (radius-at 0.0))) 1.0e-9) "progress 0 -> base-radius only")
    (is (< (Math/abs (- 5.0 (radius-at 100.0))) 1.0e-9) "progress 1 -> base-radius + radius-growth")
    (is (< (Math/abs (- 3.0 (radius-at 50.0))) 1.0e-9) "progress 0.5 -> halfway")))

(deftest charge-ring-progress-clamps-past-max-test
  (let [{:keys [sink batches]} (collecting-sink)]
    (vm/sample-node! {:component :vfx.fx/charge-ring
                      :center {:x 0.0 :y 0.0 :z 0.0}
                      :charge-ticks 999.0 :max-charge-ticks 100.0
                      :segments 4 :base-radius 1.0 :radius-growth 4.0
                      :pulse-amplitude 0.0 :pulse-frequency 0.0
                      :color [1 1 1 1]}
                     {:input {} :seed 0 :sink sink})
    (let [p (:p1 (first (:ops (first (:payload (first @batches))))))
          radius (Math/sqrt (+ (Math/pow (.-x ^V3 p) 2) (Math/pow (.-z ^V3 p) 2)))]
      (is (< (Math/abs (- 5.0 radius)) 1.0e-9) "progress clamps to 1.0, radius never exceeds base+growth"))))

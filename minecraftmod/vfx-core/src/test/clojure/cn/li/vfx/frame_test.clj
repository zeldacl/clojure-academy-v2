(ns cn.li.vfx.frame-test
  "Proves cn.li.vfx.frame/->java-frame translates cn.li.vfx.scene/sample!'s
   own op shapes into the SAME VfxBatch/VfxOutput/VfxFrame shape
   ability-runtime's compose.clj and every loader's renderer already
   consume from the old engine -- checked against real field values, not
   just \"doesn't throw\"."
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.vfx.frame :as frame])
  (:import [cn.li.mcmod.runtime.vfx VfxRenderStage]))

(defn- one-instance [ops]
  {[:k] {:scene ops :emitters []}})

(deftest ring-op-becomes-a-line-primitive-batch-test
  (let [f (frame/->java-frame 1 0 (one-instance
                                    [{:kind :ring :center {:vec3 [1.0 2.0 3.0]}
                                      :radius 4.0 :segments 8 :color [255 0 0 255] :alpha 0.5}]))]
    (is (= 1 (count (.batches f))))
    (let [batch (first (.batches f))]
      (is (identical? VfxRenderStage/WORLD_AFTER_TRANSLUCENT (.stage batch)))
      (is (= "line" (.primitive batch)))
      (is (= :ring (get-in (.payload batch) [:geometry :kind])))
      (is (= 0.5 (get-in (.payload batch) [:material :alpha]))))))

(deftest beam-op-carries-layers-as-material-test
  (let [f (frame/->java-frame 1 0 (one-instance
                                    [{:kind :beam :start {:vec3 [0.0 0.0 0.0]} :end {:vec3 [1.0 0.0 0.0]}
                                      :layers {:color [0 255 0 255]} :alpha 1.0}]))
        batch (first (.batches f))]
    (is (= "quad" (.primitive batch)))
    (is (= [0 255 0 255] (get-in (.payload batch) [:material :color])))))

(deftest beam-op-accepts-v4-layer-vector-test
  "Regression: arc-gen / beam-arc-fade pass :layers as a vector of layer
   maps. assoc onto that vector threw 'Key must be integer'."
  (let [layers [{:shape :tube :radius 0.08 :color [236 170 93 60]}
                {:shape :line :width 0.015 :color [165 230 255 160]}]
        f (frame/->java-frame 1 0 (one-instance
                                    [{:kind :beam :start {:vec3 [0.0 0.0 0.0]} :end {:vec3 [1.0 0.0 0.0]}
                                      :layers layers :alpha 1.0}]))
        material (get-in (.payload (first (.batches f))) [:material])]
    (is (= [236 170 93 60] (:color material)))
    (is (= layers (:layers material)))
    (is (= 1.0 (:alpha material)))))

(deftest beam-op-rejects-invalid-layers-loudly-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"must be a map or a sequence"
                        (frame/->java-frame 1 0 (one-instance
                                                  [{:kind :beam :start {:vec3 [0.0 0.0 0.0]}
                                                    :end {:vec3 [1.0 0.0 0.0]}
                                                    :layers "bad"}])))))

(deftest arc-op-becomes-a-quad-primitive-batch-test
  "Regression: main arc-gen zigzag bolts must cross the frame ABI as :quad
   batches with geometry :kind :arc for the neutral render plan to expand."
  (let [f (frame/->java-frame 1 0 (one-instance
                                    [{:kind :arc
                                      :start {:x 0.0 :y 1.0 :z 0.0}
                                      :end {:x 0.0 :y 1.0 :z 5.0}
                                      :pattern :weak :seed 3
                                      :life-ratio 0.1 :alpha 1.0}]))
        batch (first (.batches f))]
    (is (= 1 (count (.batches f))))
    (is (= "quad" (.primitive batch)))
    (is (= :arc (get-in (.payload batch) [:geometry :kind])))
    (is (= :weak (get-in (.payload batch) [:geometry :pattern])))
    (is (= "academy:textures/effects/arc/line_segment.png"
           (get-in (.payload batch) [:material :texture])))))

(deftest quad-op-passes-geometry-and-material-through-test
  (let [f (frame/->java-frame 1 0 (one-instance
                                    [{:kind :quad :geometry {:p0 :a :p1 :b :p2 :c :p3 :d}
                                      :material {:texture "x"}}]))
        batch (first (.batches f))]
    (is (= "quad" (.primitive batch)))
    (is (= {:p0 :a :p1 :b :p2 :c :p3 :d} (get-in (.payload batch) [:geometry])))))

(deftest audio-one-shot-op-becomes-an-output-not-a-batch-test
  (let [f (frame/->java-frame 1 0 (one-instance
                                    [{:kind :audio-one-shot :sound-id "academy:boom"
                                      :volume 0.8 :pitch 1.0 :position {:vec3 [0.0 0.0 0.0]}}]))]
    (is (empty? (.batches f)))
    (is (= 1 (count (.outputs f))))
    (let [output (first (.outputs f))]
      (is (identical? cn.li.mcmod.runtime.vfx.VfxOutputKind/AUDIO (.kind output)))
      (is (= (float 0.8) (.amount output)))
      (is (= "academy:boom" (.resourceId output))))))

(deftest camera-shake-op-becomes-a-camera-output-test
  (let [f (frame/->java-frame 1 0 (one-instance
                                    [{:kind :camera-shake :amplitude 0.4 :duration 10.0}]))
        output (first (.outputs f))]
    (is (identical? cn.li.mcmod.runtime.vfx.VfxOutputKind/CAMERA (.kind output)))
    (is (= (float 0.4) (.amount output)))))

(deftest first-person-motion-op-emits-interpolated-transform-test
  "V4 hand-motion curves cross the neutral frame ABI as a first-person batch.
   The platform renderer consumes the transform map without knowing the curve
   representation, so interpolation remains deterministic and renderer-neutral."
  (let [f (frame/->java-frame 1 0
                              (one-instance
                               [{:kind :first-person-motion
                                 :stage :punch
                                 :phase-ticks 3
                                 :duration-ticks 6
                                 :curves {:punch {:tx [[0.0 0.0] [1.0 0.8]]
                                                 :rot-x [[0.0 -40.0] [1.0 0.0]]}}}]))
        batch (first (.batches f))
        transform (first (.payload batch))]
    (is (identical? VfxRenderStage/FIRST_PERSON (.stage batch)))
    (is (= "first-person" (.primitive batch)))
    (is (= 0.4 (:tx transform)))
    (is (= -20.0 (:rot-x transform)))
    (is (= 0.0 (:rot-y transform)))))
(deftest emitter-op-produces-a-quad-batch-with-no-corners-matching-old-live-no-op-test
  (testing "reproduces the old engine's own confirmed no-op :vfx/emitter rendering
            (quad-ops requires :p0..:p3, which emitter geometry never has) -- behavioral
            parity with what is live today, not a new regression"
    (let [f (frame/->java-frame 1 0 (one-instance
                                      [{:kind :emitter :anchor {:vec3 [0.0 0.0 0.0]}
                                        :rate-per-tick 1.0 :limit 10 :particle {}}]))
          batch (first (.batches f))]
      (is (= "quad" (.primitive batch)))
      (is (nil? (:p0 (get-in (.payload batch) [:geometry])))))))

(deftest multiple-instances-and-ops-all-land-in-one-frame-test
  (let [f (frame/->java-frame 7 3
                              {[:a] {:scene [{:kind :ring :center {:vec3 [0.0 0.0 0.0]} :radius 1.0}]}
                               [:b] {:scene [{:kind :audio-one-shot :sound-id "s" :position {:vec3 [0.0 0.0 0.0]}}
                                             {:kind :post-process :effect :blur}]}})]
    (is (= 7 (.frameId f)))
    (is (= 3 (.resourceGeneration f)))
    (is (= 1 (count (.batches f))))
    (is (= 2 (count (.outputs f))))))

(deftest particle-emitter-buffer-becomes-a-particle-batch-test
  (let [buffer (cn.li.mcmod.runtime.vfx.ParticleColumns. 8 1 0)
        layout {:id :sparks :capacity 8 :columns {:position [0 1 2]}}
        f (frame/->java-frame 2 0 {[:k] {:scene []
                                        :emitters [{:layout layout :buffer buffer}]}})
        batch (first (.batches f))]
    (is (= 1 (count (.batches f))))
    (is (= "particle" (.primitive batch)))
    (is (= 0 (.instanceCount batch)))
    (is (= buffer (get-in (.payload batch) [:particles])))
    (is (= layout (get-in (.payload batch) [:layout])))))

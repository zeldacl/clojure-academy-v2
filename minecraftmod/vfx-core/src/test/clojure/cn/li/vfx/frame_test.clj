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
    (is (= "line" (.primitive batch)))
    (is (= [0 255 0 255] (get-in (.payload batch) [:material :color])))))

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

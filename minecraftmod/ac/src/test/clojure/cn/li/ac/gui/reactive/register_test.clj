(ns cn.li.ac.gui.reactive.register-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.gui.reactive.register :as register]
            [cn.li.ac.client.effect-controller :as effect-controller])
  (:import [cn.li.mcmod.runtime FramePacket RenderPass RenderStage RenderCommand$Quad]))

(defn- empty-ui-packet []
  (FramePacket. 1 [(RenderPass. RenderStage/HUD [(RenderCommand$Quad. 0.0 0.0 8.0 8.0 -1)])]))

(deftest merge-vfx-passes-is-a-noop-without-a-vfx-context
  (testing "HUD/Screen calls never carry a :presentation-context, so the UI packet passes through untouched"
    (let [packet (empty-ui-packet)]
      (is (identical? packet (#'register/merge-vfx-passes packet nil 1 0.0))))))

(deftest merge-vfx-passes-folds-sampled-world-batches-into-the-ui-packet
  (effect-controller/reset-for-test!)
  (try
    (effect-controller/register-effect!
      :register-test-effect
      {:level {:initial-state (fn [] {})
               :build-plan-fn (fn [_cam _hand _tick _query-fn]
                                {:ops [{:kind :line}]})}})
    (let [packet (empty-ui-packet)
          vfx-context {:camera-pos {:x 0.0 :y 0.0 :z 0.0}
                       :hand-center-pos {}
                       :tick 0
                       :query-nearby-blocks-fn (fn [& _] [])}
          merged (#'register/merge-vfx-passes packet vfx-context 1 0.0)]
      (testing "the original UI pass survives untouched"
        (is (= 2 (count (.passes merged))))
        (is (some #(= RenderStage/HUD (.stage ^RenderPass %)) (.passes merged))))
      (testing "a world-after-translucent pass was appended with the sampled batch"
        (let [world-pass (first (filter #(= RenderStage/WORLD_AFTER_TRANSLUCENT (.stage ^RenderPass %))
                                        (.passes merged)))]
          (is (some? world-pass))
          (is (= 1 (count (.commands ^RenderPass world-pass))))
          (is (= "mesh" (.primitive ^cn.li.mcmod.runtime.RenderCommand$Batch
                                    (first (.commands ^RenderPass world-pass))))))))
    (finally
      (effect-controller/reset-for-test!))))

(deftest merge-vfx-passes-degrades-to-the-ui-packet-on-sampling-failure
  (testing "an unmapped VFX stage must not take down the whole frame merge"
    (let [packet (empty-ui-packet)]
      (with-redefs [effect-controller/sample-frame!
                    (fn [_context] {:stages {:not-a-mapped-stage [{:stage :not-a-mapped-stage
                                                                    :primitive :mesh :count 1
                                                                    :payload []}]}})]
        (is (identical? packet (#'register/merge-vfx-passes packet {} 1 0.0)))))))

(ns cn.li.ability.compose-test
  "Regression: arc-gen's audio-one-shot VfxOutput must fold into RenderPass
   without putting nil commands into List.copyOf (NPE at RenderPass.<init>)."
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ability.compose :as compose]
            [cn.li.vfx.frame :as frame])
  (:import [cn.li.mcmod.runtime FramePacket RenderStage]
           [cn.li.mcmod.runtime.vfx VfxFrame VfxOutput VfxOutputKind VfxRenderStage VfxBatch]
           [java.util ArrayList]))

(defn- empty-ui-packet []
  (FramePacket. 1 (make-array cn.li.mcmod.runtime.ui.UiDrawList 0) []))

(deftest merge-vfx-audio-and-arc-batches-without-npe
  (let [vfx (frame/->java-frame
             1 0
             {[:k] {:scene [{:kind :arc
                             :start {:x 0.0 :y 1.0 :z 0.0}
                             :end {:x 0.0 :y 1.0 :z 5.0}
                             :pattern :weak :seed 1
                             :hand-origin? true
                             :source-player-id "player-1"
                             :life-ratio 0.0 :alpha 1.0}
                            {:kind :audio-one-shot
                             :sound-id "academy:em.arc_weak"
                             :volume 0.5 :pitch 1.0
                             :position {:x 0.0 :y 1.0 :z 0.0}}]
                    :emitters []}})
        packet (compose/merge-vfx-into-frame (empty-ui-packet) vfx)
        passes (.passes packet)
        stages (set (map #(.stage %) passes))]
    (is (contains? stages RenderStage/WORLD_AFTER_TRANSLUCENT))
    (is (contains? stages RenderStage/AUDIO))
    (is (every? (fn [pass]
                  (every? some? (.commands pass)))
                passes))))

(deftest merge-vfx-rejects-nil-command-loudly
  (let [batches (doto (ArrayList.)
                  (.add (VfxBatch. VfxRenderStage/WORLD_AFTER_TRANSLUCENT
                                   0 "quad" 0 nil {:operation :draw-batch})))
        outputs (doto (ArrayList.)
                  (.add (VfxOutput. VfxOutputKind/AUDIO 1 (float 0.5) "academy:em.arc_weak")))
        vfx (VfxFrame. 1 0 batches outputs)]
    (is (some? (compose/merge-vfx-into-frame (empty-ui-packet) vfx)))))

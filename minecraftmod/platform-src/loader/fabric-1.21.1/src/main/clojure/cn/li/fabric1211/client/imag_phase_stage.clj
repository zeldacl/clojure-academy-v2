(ns cn.li.fabric1211.client.imag-phase-stage
  "CLIENT-ONLY: post-translucent stage hook.

  Content owns the geometry and the queue; this loader only subscribes the
  event and delegates to the shared neutral seam
  (cn.li.platform.neutral.world-render-stage), the same division of labour as
  cn.li.fabric1211.client.presentation-world-renderer.

  The stage exists because block entities render before the translucent terrain
  layer: a block whose geometry has to composite over an opaque-but-later
  surface (the Imag Phase pool's black fluid top) cannot draw in the BE pass at
  all. WorldRenderEvents/AFTER_TRANSLUCENT is Fabric's equivalent of forge's
  RenderLevelStageEvent AFTER_TRANSLUCENT_BLOCKS and is the same phase the
  presentation world renderer submits `:world-after-translucent` on.

  The immediate flush is load-bearing: without it the batch sits pending in the
  BufferSource until the next flush-all, which under FABULOUS lands at the
  START of the next frame's translucent phase — where the translucent target is
  cleared right after, erasing the geometry entirely."
  (:require [cn.li.platform.neutral.world-render-stage :as world-render-stage]
            [cn.li.mc1211.client.player-state-core :as player-state]
            [cn.li.mcmod.runtime.install :as install]
            [cn.li.mcmod.util.log :as log])
  (:import [com.mojang.blaze3d.vertex PoseStack]
           [net.fabricmc.fabric.api.client.rendering.v1 WorldRenderContext WorldRenderEvents WorldRenderEvents$AfterTranslucent]
           [net.minecraft.client Minecraft]
           [net.minecraft.client.renderer MultiBufferSource$BufferSource]))

(defn- on-after-translucent [^WorldRenderContext ctx]
  ;; Cheapest guard first: until some content installs a drain there is nothing
  ;; to do, and this fires every frame.
  (when (world-render-stage/installed?)
    (try
      (when-let [^Minecraft mc (Minecraft/getInstance)]
        (when-let [camera-pos (player-state/camera-position)]
          ;; Deliberately NOT (.consumers ctx): that is typed MultiBufferSource,
          ;; and the flush below needs a real BufferSource. During the world
          ;; render pass this is the same object.
          (let [^PoseStack pose-stack (.matrixStack ctx)
                ^MultiBufferSource$BufferSource buffer-source
                (.bufferSource (.renderBuffers mc))]
            (when (and pose-stack buffer-source)
              (when (pos? (world-render-stage/drain-all!
                            {:pose-stack pose-stack
                             :buffer-source buffer-source
                             :camera-pos camera-pos}))
                ;; See the ns docstring: the batch must draw NOW, while the
                ;; current output target is still the right one.
                (.endBatch buffer-source))))))
      (catch Exception e
        (log/debug "Post-translucent world render stage failed:" (ex-message e))))))

(defn init! []
  (install/process-once! ::stage-listener-registered
    #(.register WorldRenderEvents/AFTER_TRANSLUCENT
                (reify WorldRenderEvents$AfterTranslucent
                  (afterTranslucent [_ ctx] (on-after-translucent ctx))))))

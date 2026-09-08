(ns cn.li.fabric262.client.imag-phase-stage
  "CLIENT-ONLY: post-translucent stage hook for the 26.2 submit-node pipeline.

  Content owns the geometry and the queue; this loader only subscribes the
  event and delegates to the shared neutral seam
  (cn.li.platform.neutral.world-render-stage), the same division of labour as
  cn.li.fabric262.client.presentation-world-renderer.

  The stage exists because block entities render before the translucent terrain
  layer: a block whose geometry has to composite over an opaque-but-later
  surface (the Imag Phase pool's black fluid top) cannot draw in the BE pass at
  all. LevelRenderEvents/AFTER_TRANSLUCENT_TERRAIN is 26.2's equivalent of the
  older WorldRenderEvents/AFTER_TRANSLUCENT.

  26.2 differs from the 1.20/1.21 hooks in what a \"buffer source\" is. There is
  no MultiBufferSource to flush here: geometry is recorded into submit nodes and
  replayed by the engine. cn.li.mc262.client.render.buffer resolves buffers
  through SubmitNodeRenderBufferAdapter/require, so the adapter itself is what
  content must receive as `:buffer-source`, and `.finish` — not `.endBatch` — is
  what closes the batch. This mirrors ScriptedBlockEntityBer.submit, which
  builds and finishes an adapter around exactly the same render call."
  (:require [cn.li.platform.neutral.world-render-stage :as world-render-stage]
            [cn.li.mc262.client.player-state-core :as player-state]
            [cn.li.mcmod.runtime.install :as install]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.mc262.client.render SubmitNodeRenderBufferAdapter]
           [net.fabricmc.fabric.api.client.rendering.v1.level LevelRenderEvents
            LevelRenderEvents$AfterTranslucentTerrain LevelRenderContext]))

(defn- on-after-translucent-terrain [^LevelRenderContext context]
  ;; Cheapest guard first: until some content installs a drain there is nothing
  ;; to do, and building an adapter allocates.
  (when (world-render-stage/installed?)
    (try
      (when-let [camera-pos (player-state/camera-position)]
        (let [pose-stack (.poseStack context)
              collector (.submitNodeCollector context)]
          (when (and pose-stack collector)
            (let [adapter (SubmitNodeRenderBufferAdapter. collector pose-stack)]
              (try
                (world-render-stage/drain-all!
                  {:pose-stack pose-stack
                   :buffer-source adapter
                   :camera-pos camera-pos})
                ;; The 26.2 analogue of endBatch. Unconditional and in a
                ;; finally: an adapter that recorded nothing finishes as a
                ;; no-op, but one left unfinished after a throw would reject
                ;; every later buffer request with "submission finished".
                (finally
                  (.finish adapter)))))))
      (catch Exception e
        (log/debug "Post-translucent world render stage failed:" (ex-message e))))))

(defn init! []
  (install/process-once! ::stage-listener-registered
    #(.register LevelRenderEvents/AFTER_TRANSLUCENT_TERRAIN
                (reify LevelRenderEvents$AfterTranslucentTerrain
                  (afterTranslucentTerrain [_ context]
                    (on-after-translucent-terrain context))))))

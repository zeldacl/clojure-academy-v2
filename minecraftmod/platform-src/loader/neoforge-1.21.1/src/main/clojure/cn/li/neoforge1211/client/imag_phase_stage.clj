(ns cn.li.neoforge1211.client.imag-phase-stage
  "CLIENT-ONLY: post-translucent stage hook.

  Content owns the geometry and the queue; this loader only subscribes the
  event and delegates to the shared neutral seam
  (cn.li.platform.neutral.world-render-stage), the same division of labour as
  cn.li.neoforge1211.client.presentation-world-renderer.

  The stage exists because block entities render before the translucent terrain
  layer: a block whose geometry has to composite over an opaque-but-later
  surface (the Imag Phase pool's black fluid top) cannot draw in the BE pass at
  all. AFTER_TRANSLUCENT_BLOCKS is the phase that matches Fabric's
  WorldRenderEvents/AFTER_TRANSLUCENT, and is what the presentation world
  renderer on this loader already uses.

  Only that one stage is subscribed. The forge-1.20.1 hook also takes
  AFTER_PARTICLES, on the strength of a bytecode check that it is dispatched
  directly in renderLevel on THAT version; no such check has been done here, so
  this stays on the single documented stage. Draining is idempotent either way
  (the queues empty on the first call), so the second subscription was only
  ever belt-and-braces.

  The immediate flush is load-bearing: without it the batch sits pending in the
  BufferSource until the next flush-all, which under FABULOUS lands at the
  START of the next frame's translucent phase — where the translucent target is
  cleared right after, erasing the geometry entirely."
  (:require [cn.li.platform.neutral.world-render-stage :as world-render-stage]
            [cn.li.mc1211.client.player-state-core :as player-state]
            [cn.li.mcmod.runtime.install :as install]
            [cn.li.mcmod.util.log :as log])
  (:import [com.mojang.blaze3d.vertex PoseStack]
           [net.minecraft.client Minecraft]
           [net.minecraft.client.renderer MultiBufferSource$BufferSource]
           [net.neoforged.neoforge.client.event RenderLevelStageEvent RenderLevelStageEvent$Stage]
           [net.neoforged.neoforge.common NeoForge]
           [net.neoforged.bus.api EventPriority]))

(defn- stage-eligible? [^RenderLevelStageEvent evt]
  ;; Stage is a fixed enum-like singleton set — identical? avoids a fresh
  ;; string allocation on every stage dispatch (~9 per frame), most of which
  ;; are not eligible.
  (identical? (.getStage evt) RenderLevelStageEvent$Stage/AFTER_TRANSLUCENT_BLOCKS))

(defn- on-render-level-stage [^RenderLevelStageEvent evt]
  ;; Cheapest guard first: until some content installs a drain there is nothing
  ;; to do, and this fires many times per frame.
  (when (and (world-render-stage/installed?) (stage-eligible? evt))
    (try
      (when-let [^Minecraft mc (Minecraft/getInstance)]
        (when-let [camera-pos (player-state/camera-position)]
          (let [^PoseStack pose-stack (.getPoseStack evt)
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
    #(.addListener (NeoForge/EVENT_BUS)
                   EventPriority/NORMAL false RenderLevelStageEvent
                   (reify java.util.function.Consumer
                     (accept [_ evt] (on-render-level-stage evt))))))

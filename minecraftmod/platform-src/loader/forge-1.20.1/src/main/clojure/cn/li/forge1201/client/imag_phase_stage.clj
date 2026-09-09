(ns cn.li.forge1201.client.imag-phase-stage
  "CLIENT-ONLY: post-translucent stage hook.

  Content owns the geometry and the queue; this loader only subscribes the
  event and delegates to the shared neutral seam
  (cn.li.platform.neutral.world-render-stage), the same division of labour as
  cn.li.forge1201.client.presentation-world-renderer.

  The stage exists because the block-entity pass runs BEFORE the translucent
  terrain layer in 1.20.1: the Imag Phase pool's opaque black fluid surface
  draws over anything the BE pass emitted and hides it. This hook fires at
  RenderLevelStageEvent AFTER_TRANSLUCENT_BLOCKS (after the fluid surface,
  matching upstream's pass-1 TESR position), drains the per-frame queues and
  flushes the buffer source immediately.

  The immediate flush is load-bearing: without it the batch sits pending in the
  BufferSource until the next flush-all — on FANCY graphics that lands right
  after the translucent layer (flash ends up visible, one frame late) but on
  FABULOUS it lands at the START of the NEXT frame's translucent phase, where
  the translucent target is cleared right after, erasing the flash entirely.
  Flushing here draws with the current output target (the translucent target
  while the terrain's render state is still active, the main target otherwise)
  so the flash composites over the surface and is blitted with it."
  (:require [cn.li.platform.neutral.world-render-stage :as world-render-stage]
            [cn.li.mc1201.client.player-state-core :as player-state]
            [cn.li.mcmod.runtime.install :as install]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.client Minecraft]
           [net.minecraftforge.client.event RenderLevelStageEvent RenderLevelStageEvent$Stage]
           [net.minecraftforge.common MinecraftForge]
           [net.minecraftforge.eventbus.api EventPriority]))

;; AFTER_TRANSLUCENT_BLOCKS fires at the end of each renderSectionLayer
;; (translucent) — twice per frame (the 1.20.1 level renders the translucent
;; layer in two passes). AFTER_PARTICLES is dispatched directly in renderLevel
;; (bytecode-verified) and also lands after the fluid surface. Subscribing to
;; both is safe: `drain-all!` empties the per-frame queues on the first fire,
;; so the later fires in the same frame find them empty and draw nothing.
(def ^:private stage-eligible?
  (fn [^RenderLevelStageEvent evt]
    (let [stage (.getStage evt)]
      (or (identical? stage RenderLevelStageEvent$Stage/AFTER_TRANSLUCENT_BLOCKS)
          (identical? stage RenderLevelStageEvent$Stage/AFTER_PARTICLES)))))

(defn- on-render-level-stage [^RenderLevelStageEvent evt]
  ;; Cheapest guard first: the stage fires ~9 times per frame and, until some
  ;; content installs a drain, every one of them has nothing to do.
  (when (and (world-render-stage/installed?) (stage-eligible? evt))
    (try
      (when-let [^Minecraft mc (Minecraft/getInstance)]
        (when-let [camera-pos (player-state/camera-position)]
          (let [buffer-source (.bufferSource (.renderBuffers mc))]
            ;; Flush only when a queue actually had items — the stage fires
            ;; twice per frame and the second fire finds them empty (drained
            ;; at the first), so its flush would be a pointless no-op.
            (when (pos? (world-render-stage/drain-all!
                          {:pose-stack (.getPoseStack evt)
                           :buffer-source buffer-source
                           :camera-pos camera-pos}))
              ;; See the ns docstring: the batch must draw NOW, while the
              ;; current output target is still the right one (the translucent
              ;; target under FABULOUS, the main target under FANCY).
              (.endBatch buffer-source)))))
      (catch Exception e
        (log/debug "Post-translucent world render stage failed:" (ex-message e))))))

(defn init! []
  (install/process-once! ::stage-listener-registered
    #(.addListener (MinecraftForge/EVENT_BUS)
                   EventPriority/NORMAL false RenderLevelStageEvent
                   (reify java.util.function.Consumer
                     (accept [_ evt] (on-render-level-stage evt))))))

(ns cn.li.fabric1211.client.presentation-world-renderer
  "CLIENT-ONLY world Presentation Runtime submission adapter."
  (:require [cn.li.mc1211.client.effects.presentation-world :as geometry]
            [cn.li.platform.neutral.vfx :as vfx]
            [cn.li.mcmod.runtime.install :as install]
            [cn.li.mcmod.util.log :as log])
  (:import [com.mojang.blaze3d.vertex PoseStack]
           [net.fabricmc.fabric.api.client.rendering.v1 WorldRenderContext WorldRenderEvents WorldRenderEvents$AfterTranslucent]
           [net.minecraft.client Minecraft]
           [net.minecraft.client.player LocalPlayer]
           [net.minecraft.client.renderer MultiBufferSource$BufferSource]))

(defn- on-after-translucent-render [^WorldRenderContext ctx]
  (try
    (when-let [^Minecraft mc (Minecraft/getInstance)]
      (when-let [^LocalPlayer player (.player mc)]
        (let [camera (.camera ctx)
              cam-vec (.getPosition camera)
              cam-pos {:x (.-x cam-vec) :y (.-y cam-vec) :z (.-z cam-vec)}
              tick (.getGameTime (.level player))
              ^PoseStack pose-stack (.matrixStack ctx)
              ^MultiBufferSource$BufferSource buffer-source (or (.consumers ctx)
                                                                 (.bufferSource (.renderBuffers mc)))]
          (when (and pose-stack buffer-source)
            (let [backend-context
                  {:draw-mesh!
                   (fn [_ _ _ _ _ payload]
                     (geometry/render-presentation-geometry!
                       {:player player
                        :pose-stack pose-stack
                        :buffer-source buffer-source
                        :camera-pos cam-pos
                        :tick tick
                        :plan payload}))}
                  frame-context (geometry/presentation-frame-context player cam-pos tick)]
              (let [frame-id (vfx/next-frame-id)
                    frame (vfx/sample-frame!
                            (assoc frame-context :frame-id frame-id :partial-tick 0.0))]
                (try
                  (doseq [batch (or (vfx/frame-stage frame-id :world-after-translucent) [])
                          :when (= :mesh (:primitive batch))
                          plan (or (:payload batch) [])]
                    (geometry/render-presentation-geometry!
                      {:player player :pose-stack pose-stack :buffer-source buffer-source
                       :camera-pos cam-pos :tick tick :plan plan}))
                  frame
                  (finally (vfx/release-frame! frame-id))))))))
    (catch Exception e
      (log/error "Fabric level effect render failed" e))))

(defn init!
  []
  (install/process-once! ::render-listener-registered
    #(.register WorldRenderEvents/AFTER_TRANSLUCENT
                (reify WorldRenderEvents$AfterTranslucent
                  (afterTranslucent [_ context]
                    (on-after-translucent-render context)))))
  (log/info "Fabric level effect renderer initialized"))

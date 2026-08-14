(ns cn.li.fabric1201.client.presentation-world-renderer
  "CLIENT-ONLY world Presentation Runtime submission adapter."
  (:require [cn.li.mc1201.client.effects.presentation-world :as geometry]
            [cn.li.platform.neutral.presentation :as presentation]
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
              (presentation/submit-current-frame!
                :world-after-translucent 0.0 0 0
                {:presentation-context frame-context
                 :backend-context backend-context}))))))
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

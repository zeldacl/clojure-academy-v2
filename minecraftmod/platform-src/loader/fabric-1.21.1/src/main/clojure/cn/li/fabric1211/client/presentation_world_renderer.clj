(ns cn.li.fabric1211.client.presentation-world-renderer
  "CLIENT-ONLY world Presentation Runtime submission adapter."
  (:require [cn.li.mc1211.client.effects.presentation-world :as geometry]
            [cn.li.platform.neutral.vfx :as vfx]
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
      (vfx/observe-resource-manager!
       (try (some-> (clojure.lang.Reflector/invokeInstanceMethod mc "getResourceManager" (object-array 0))
                    System/identityHashCode)
            (catch Throwable _ nil)))
      (when-let [^LocalPlayer player (.player mc)]
        (let [camera (.camera ctx)
              cam-vec (.getPosition camera)
              cam-pos {:x (.-x cam-vec) :y (.-y cam-vec) :z (.-z cam-vec)}
              tick (.getGameTime (.level player))
              ^PoseStack pose-stack (.matrixStack ctx)
              ^MultiBufferSource$BufferSource buffer-source (or (.consumers ctx)
                                                                 (.bufferSource (.renderBuffers mc)))]
          (when (and pose-stack buffer-source)
            (let [frame-context (geometry/presentation-frame-context player cam-pos tick)
                  ;; Must match the HUD callback's own width/height (see
                  ;; presentation_hud_renderer.clj): extract! memoizes per
                  ;; real frame regardless of which stage asks first, so a
                  ;; mismatched size here would bleed into the HUD's cached
                  ;; layout.
                  w (.getGuiScaledWidth (.getWindow mc))
                  h (.getGuiScaledHeight (.getWindow mc))]
              (presentation/submit-current-frame!
                :world-after-translucent 0.0 w h
                {:presentation-context frame-context
                 :backend-context
                 {:draw-batch!
                  (fn [_g _stage prim _mat _var _cnt payload]
                    (when (= "mesh" prim)
                      (doseq [plan (or payload [])]
                        (geometry/render-presentation-geometry!
                          {:player player :pose-stack pose-stack :buffer-source buffer-source
                           :camera-pos cam-pos :tick tick :plan plan}))))}}))))))
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

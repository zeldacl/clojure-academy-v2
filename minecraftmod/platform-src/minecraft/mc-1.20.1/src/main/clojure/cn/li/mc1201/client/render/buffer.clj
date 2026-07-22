(ns cn.li.mc1201.client.render.buffer
  "CLIENT-ONLY shared render-buffer helpers for Minecraft 1.20.1."
  (:import [net.minecraft.client.renderer MultiBufferSource RenderType]
           [net.minecraft.resources ResourceLocation]))

(defn get-solid-buffer
  [^MultiBufferSource buffer-source ^ResourceLocation texture]
  (.getBuffer buffer-source (RenderType/entityCutoutNoCull texture)))

(defn get-translucent-buffer
  [^MultiBufferSource buffer-source ^ResourceLocation texture]
  (.getBuffer buffer-source (RenderType/entityTranslucent texture)))

(defn get-cutout-no-cull-buffer
  [^MultiBufferSource buffer-source ^ResourceLocation texture]
  (.getBuffer buffer-source (RenderType/entityCutoutNoCull texture)))

(defn get-entity-buffer
  [buffer-source render-mode texture]
  (case render-mode
    :solid (get-solid-buffer buffer-source texture)
    :translucent (get-translucent-buffer buffer-source texture)
    :cutout-no-cull (get-cutout-no-cull-buffer buffer-source texture)
    (get-solid-buffer buffer-source texture)))

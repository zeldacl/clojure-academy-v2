(ns cn.li.forge1201.client.render-buffer-impl
  "CLIENT-ONLY: Client-side render buffer implementation for Forge 1.20.1.

  This namespace wraps net.minecraft.client.renderer.RenderType and MultiBufferSource
  and must only be loaded on the client side via side-checked requiring-resolve."
  (:import [net.minecraft.client.renderer MultiBufferSource RenderType]
           [net.minecraft.resources ResourceLocation]))

(defn get-solid-buffer
  "Get a solid entity render buffer.

  Args:
    buffer-source: MultiBufferSource - The buffer source
    texture: ResourceLocation - Texture to use

  Returns:
    VertexConsumer for solid rendering"
  [^MultiBufferSource buffer-source ^ResourceLocation texture]
  (.getBuffer buffer-source (RenderType/entitySolid texture)))

(defn get-translucent-buffer
  "Get a translucent entity render buffer.

  Args:
    buffer-source: MultiBufferSource - The buffer source
    texture: ResourceLocation - Texture to use

  Returns:
    VertexConsumer for translucent rendering"
  [^MultiBufferSource buffer-source ^ResourceLocation texture]
  (.getBuffer buffer-source (RenderType/entityTranslucent texture)))

(defn get-cutout-no-cull-buffer
  "Get a cutout (no culling) entity render buffer.

  Args:
    buffer-source: MultiBufferSource - The buffer source
    texture: ResourceLocation - Texture to use

  Returns:
    VertexConsumer for cutout rendering"
  [^MultiBufferSource buffer-source ^ResourceLocation texture]
  (.getBuffer buffer-source (RenderType/entityCutoutNoCull texture)))

(defn get-entity-buffer
  "Get an entity render buffer based on render mode.

  Args:
    buffer-source: MultiBufferSource - The buffer source
    render-mode: Keyword - One of :solid, :translucent, :cutout-no-cull
    texture: ResourceLocation - Texture to use

  Returns:
    VertexConsumer for the specified render mode"
  [buffer-source render-mode texture]
  (case render-mode
    :solid (get-solid-buffer buffer-source texture)
    :translucent (get-translucent-buffer buffer-source texture)
    :cutout-no-cull (get-cutout-no-cull-buffer buffer-source texture)
    (get-solid-buffer buffer-source texture)))

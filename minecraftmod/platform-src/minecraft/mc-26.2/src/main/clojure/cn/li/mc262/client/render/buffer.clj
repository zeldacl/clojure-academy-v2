(ns cn.li.mc262.client.render.buffer
  "CLIENT-ONLY render-buffer adapter for the Minecraft 26.2 submit-node pipeline."
  (:import [cn.li.mc262.client.render SubmitNodeRenderBufferAdapter]
           [net.minecraft.client.renderer.rendertype RenderTypes]
           [net.minecraft.resources Identifier]))

(defn- adapter
  ^SubmitNodeRenderBufferAdapter [buffer-source]
  (SubmitNodeRenderBufferAdapter/require buffer-source))

(defn get-solid-buffer
  [buffer-source ^Identifier texture]
  (.getBuffer (adapter buffer-source) (RenderTypes/entitySolid texture)))

(defn get-translucent-buffer
  [buffer-source ^Identifier texture]
  (.getBuffer (adapter buffer-source) (RenderTypes/entityTranslucent texture)))

(defn get-cutout-no-cull-buffer
  [buffer-source ^Identifier texture]
  (.getBuffer (adapter buffer-source) (RenderTypes/entityCutout texture)))

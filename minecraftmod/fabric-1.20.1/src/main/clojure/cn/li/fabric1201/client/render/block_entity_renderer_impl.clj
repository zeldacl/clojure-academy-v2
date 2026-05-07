(ns cn.li.fabric1201.client.render.block-entity-renderer-impl
  "Fabric 1.20.1 Universal Block Entity Renderer

  Platform-specific BlockEntityRenderer implementation that dispatches to registered renderers.
  This class knows nothing about specific blocks - it's a pure dispatcher."
  (:require [cn.li.mcmod.client.render.tesr-api :as tesr-api]
            [cn.li.mcmod.util.log :as log]))

(gen-class
  :name cn.li.fabric1201.client.render.BlockEntityRendererImpl
  :implements [net.minecraft.client.renderer.blockentity.BlockEntityRenderer]
  :prefix "renderer-"
  :constructors {[] []})

(defn renderer-render
  "Main render method - called by Minecraft every frame"
  [_ block-entity partial-ticks pose-stack buffer-source packed-light packed-overlay]
  (try
    (tesr-api/render-tile-entity block-entity partial-ticks pose-stack buffer-source packed-light packed-overlay)
    (catch Exception e
      (log/error "Error rendering BlockEntity in Fabric:" (.getMessage e))
      (.printStackTrace e))))

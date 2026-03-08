(ns my-mod.forge1201.client.render.tesr-impl
  "Forge 1.20.1 Universal TileEntity Special Renderer

  Uses reify instead of gen-class to avoid DynamicClassLoader lifecycle issues:
  gen-class registers the class in the DynamicClassLoader active at init time,
  but resource reloads run in a fresh classloader context where that class is
  no longer reachable. reify creates an anonymous class inline at call time,
  which is always visible to the current classloader."
  (:require [my-mod.client.render.tesr-api :as tesr-api]
            [my-mod.util.log :as log])
  (:import [net.minecraft.client.renderer.blockentity BlockEntityRenderer]))

(defn new-renderer
  "Create a BlockEntityRenderer that dispatches to registered tesr-api renderers.
  Called by BlockEntityRendererProvider.create each time a renderer is needed."
  []
  (reify BlockEntityRenderer
    (render [_this block-entity _partial-ticks _pose-stack _buffer-source _packed-light _packed-overlay]
      (try
        (tesr-api/render-tile-entity block-entity 0.0 0.0 0.0)
        (catch Exception e
          (log/error "Error rendering BlockEntity in Forge 1.20.1:" (.getMessage e))
          (.printStackTrace e))))))

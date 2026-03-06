(ns my-mod.forge1201.client.render.tesr-impl
  "Forge 1.20.1 Universal TileEntity Special Renderer
  
  Platform-specific TESR implementation that dispatches to registered renderers.
  This class knows nothing about specific blocks - it's a pure dispatcher."
  (:require [my-mod.client.render.tesr-api :as tesr-api]
            [my-mod.util.log :as log]))

;; ============================================================================
;; TESR Generation
;; ============================================================================

;; Generate Java class implementing BlockEntityRenderer
;; Note: In 1.20.x, BlockEntityRenderer is an interface; Minecraft constructs renderers via provider.
(gen-class
  :name my_mod.forge1201.client.render.TileEntityRendererImpl
  :implements [net.minecraft.client.renderer.blockentity.BlockEntityRenderer]
  :prefix "renderer-"
  :constructors {[] []})

;; ============================================================================
;; Renderer Methods
;; ============================================================================

(defn renderer-render
  "Main render method - called by Minecraft every frame
  
  Dispatches to registered BlockEntity renderers based on type.
  
  Args:
  - this: TileEntityRendererImpl instance
  - block-entity: BlockEntity instance
  - partial-ticks: float - interpolation value
  - pose-stack: PoseStack
  - buffer-source: MultiBufferSource
  - packed-light: int - lighting value
  - packed-overlay: int - overlay value
  
  Returns: nil"
  [this block-entity partial-ticks pose-stack buffer-source packed-light packed-overlay]
  (try
    ;; Universal dispatcher - doesn't know about specific block types
    ;; Position is (0,0,0) - engine handles translation to block position
    (tesr-api/render-tile-entity block-entity 0.0 0.0 0.0)
    
    (catch Exception e
      (log/error "Error rendering BlockEntity in Forge 1.20.1:" (.getMessage e))
      (.printStackTrace e))))

;; ============================================================================
;; Design Notes
;; ============================================================================

;; Forge 1.20.1 Rendering:
;; - BlockEntityRenderer interface (TileEntityRenderer is deprecated)
;; - render() method receives PoseStack and MultiBufferSource
;; - Position is (0,0,0) - engine handles translation to block position
;; - Can use VertexConsumer for modern rendering
;;
;; Current implementation:
;; - Uses GL11 calls for compatibility with existing code
;; - Position is always (0,0,0) in TESR context (tile-relative)
;;
;; Future improvements:
;; - Migrate to PoseStack transformations
;; - Use MultiBufferSource for model rendering
;; - Implement proper lighting integration

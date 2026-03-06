(ns my-mod.fabric1201.client.render.block-entity-renderer-impl
  "Fabric 1.20.1 Universal Block Entity Renderer
  
  Platform-specific BlockEntityRenderer implementation that dispatches to registered renderers.
  This class knows nothing about specific blocks - it's a pure dispatcher."
  (:require [my-mod.client.render.tesr-api :as tesr-api]
            [my-mod.util.log :as log]))

;; ============================================================================
;; Block Entity Renderer Generation
;; ============================================================================

;; Generate Java class implementing BlockEntityRenderer
;; Note: This project uses official Mojang mappings on Fabric (Loom officialMojangMappings),
;; so Minecraft class names/packages match Forge (net.minecraft.client.renderer...).
(gen-class
  :name my_mod.fabric1201.client.render.BlockEntityRendererImpl
  :implements [net.minecraft.client.renderer.blockentity.BlockEntityRenderer]
  :prefix "renderer-"
  :constructors {[] []})

;; ============================================================================
;; Renderer Methods
;; ============================================================================

(defn renderer-render
  "Main render method - called by Minecraft every frame
  
  Args:
  - this: BlockEntityRendererImpl instance
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
      (log/error "Error rendering BlockEntity in Fabric:" (.getMessage e))
      (.printStackTrace e))))

;; ============================================================================
;; Design Notes
;; ============================================================================

;; Fabric 1.20.1 Rendering:
;; - BlockEntityRenderer interface instead of TileEntityRenderer
;; - render() method receives MatrixStack and VertexConsumerProvider
;; - Position is (0,0,0) - engine handles translation to block position
;; - Should ideally use VertexConsumer for rendering
;;
;; Current implementation:
;; - Uses GL11 calls for compatibility with existing code
;; - Works but is not the modern Fabric approach
;;
;; Future improvements:
;; - Migrate to MatrixStack transformations
;; - Use VertexConsumer for model rendering
;; - Implement proper lighting integration

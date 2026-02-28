(ns my-mod.fabric1201.client.render.block-entity-renderer-impl
  "Fabric 1.20.1 Universal Block Entity Renderer
  
  Platform-specific BlockEntityRenderer implementation that dispatches to registered renderers.
  This class knows nothing about specific blocks - it's a pure dispatcher."
  (:require [my-mod.client.render.tesr-api :as tesr-api]
            [my-mod.util.log :as log]))

;; ============================================================================
;; Block Entity Renderer Generation
;; ============================================================================

;; Generate Java class implementing BlockEntityRenderer<T>
;; Note: In Fabric 1.20.1, use BlockEntityRenderer interface
(gen-class
  :name my_mod.fabric1201.client.render.BlockEntityRendererImpl
  :implements [net.minecraft.client.render.block.entity.BlockEntityRenderer]
  :prefix "renderer-"
  :init init
  :state state
  :constructors {[net.minecraft.client.render.block.entity.BlockEntityRendererFactory$Context]
                 []})

;; ============================================================================
;; Renderer Methods
;; ============================================================================

(defn renderer-init
  "Constructor - stores rendering context
  
  Args:
  - context: BlockEntityRendererFactory.Context
  
  Returns: [[parent-args] state]"
  [context]
  [[] {:context context}])

(defn renderer-render
  "Main render method - called by Minecraft every frame
  
  Args:
  - this: MatrixBlockEntityRenderer instance
  - block-entity: TileMatrix instance
  - tick-delta: float - interpolation value (partial ticks)
  - matrices: MatrixStack
  - vertex-consumers: VertexConsumerProvider
  - light: int - lighting value
  - overlay: int - overlay value
  
  Returns: nil"
  [this block-entity tick-delta matrices vertex-consumers light overlay]
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

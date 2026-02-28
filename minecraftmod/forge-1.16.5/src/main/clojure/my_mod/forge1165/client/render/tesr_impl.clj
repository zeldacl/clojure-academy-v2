(ns my-mod.forge1165.client.render.tesr-impl
  "Forge 1.16.5 Universal TileEntity Special Renderer
  
  Platform-specific TESR implementation that dispatches to registered renderers.
  This class knows nothing about specific blocks - it's a pure dispatcher."
  (:require [my-mod.client.render.tesr-api :as tesr-api]
            [my-mod.util.log :as log]))

;; ============================================================================
;; TESR Generation
;; ============================================================================

;; Generate Java class extending TileEntityRenderer
;; Note: In Forge 1.16.5, TESR extends TileEntityRenderer<T>
(gen-class
  :name my_mod.forge1165.client.render.TileEntityRendererImpl
  :extends net.minecraft.client.renderer.tileentity.TileEntityRenderer
  :prefix "tesr-"
  :init init
  :state state
  :constructors {[net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher] 
                 [net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher]})

;; ============================================================================
;; TESR Methods
;; ============================================================================

(defn tesr-init
  "Constructor - calls parent TileEntityRenderer constructor
  
  Args:
  - dispatcher: TileEntityRendererDispatcher
  
  Returns: [[parent-args] state]"
  [dispatcher]
  [[dispatcher] nil])

(defn tesr-render
  "Main render method - called by Minecraft every frame
  
  Dispatches to registered TileEntity renderers based on type.
  
  Args:
  - this: MatrixTESR instance
  - tile-entity: TileEntity instance
  - partial-ticks: float - interpolation value
  - matrix-stack: MatrixStack (1.16.5+)
  - buffer-source: IRenderTypeBuffer
  - combined-light: int - lighting value
  - combined-overlay: int - overlay value
  
  Returns: nil"
  [this tile-entity partial-ticks matrix-stack buffer-source combined-light combined-overlay]
  (try
    ;; Universal dispatcher - doesn't know about specific block types
    ;; Position is always (0,0,0) - rendering is relative to tile position
    (tesr-api/render-tile-entity tile-entity 0.0 0.0 0.0)
    
    (catch Exception e
      (log/error "Error rendering TileEntity in Forge TESR:" (.getMessage e))
      (.printStackTrace e))))

;; ============================================================================
;; Design Notes
;; ============================================================================

;; Forge 1.16.5 TESR changes:
;; - render() method signature changed to include MatrixStack
;; - Position is always (0,0,0) - rendering is relative to tile position
;; - Should use VertexConsumer for rendering, but we're using GL11 for compatibility
;;
;; Migration path:
;; 1. Current: Use GL11 calls (works but deprecated)
;; 2. Future: Migrate to MatrixStack + VertexConsumer API

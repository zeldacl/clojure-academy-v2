(ns my-mod.fabric1201.client.init
  "Fabric 1.20.1 client-side initialization and registration"
  (:require [my-mod.util.log :as log]
            [my-mod.client.render.matrix-renderer :as matrix-renderer])
  (:import [net.fabricmc.fabric.api.client.rendering.v1 BlockEntityRendererRegistry]
           [net.minecraft.client.render.block.entity BlockEntityRenderer BlockEntityRendererFactory]))

;; ============================================================================
;; Client Registration
;; ============================================================================

(defn register-renderers
  "Register platform-agnostic renderers with the universal BlockEntityRenderer dispatcher
  
  Called during client initialization. Registers all block entity renderers
  by invoking their register! functions."
  []
  (log/info "Registering block renderers for Fabric 1.20.1...")
  (try
    ;; Register Matrix block renderer
    ;; The matrix-renderer/register! function:
    ;; - Registers TileMatrix with the core renderer dispatcher
    ;; - The universal BlockEntityRenderer (MatrixBlockEntityRenderer class) will dispatch to this renderer
    (matrix-renderer/register!)
    (log/info "Matrix block renderer registered successfully")
    
    (catch Exception e
      (log/error "Failed to register block renderers" e))))

(defn init-client
  "Initialize client-side systems for Fabric 1.20.1
  
  Called from Java ClientModInitializer. Registers all block entity renderers."
  []
  (log/info "Initializing Fabric 1.20.1 client-side systems")
  (register-renderers)
  (log/info "Fabric client initialization complete"))

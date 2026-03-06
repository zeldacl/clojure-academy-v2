(ns my-mod.fabric1201.client.init
  "Fabric 1.20.1 client-side initialization and registration"
  (:require [my-mod.util.log :as log]
            [my-mod.util.render :as render]
            [my-mod.client.render.matrix-renderer :as matrix-renderer]
            [my-mod.client.render.solar-renderer :as solar-renderer]
            [my-mod.fabric1201.client.render.block-entity-renderer-impl]
            [my-mod.fabric1201.mod :as fabric-mod])
  (:import [net.minecraft.client Minecraft]
           [net.minecraft.client.renderer.texture TextureManager]
           [net.minecraft.client.renderer.blockentity BlockEntityRenderers BlockEntityRendererProvider]))

;; ============================================================================
;; Client Registration
;; ============================================================================

(defn- bind-texture-fabric!
  "Bind a texture for rendering.
  
  Args:
    texture: ResourceLocation - texture to bind
  
  Uses direct method calls (no reflection) for better performance."
  [texture]
  (let [minecraft (Minecraft/getInstance)
        texture-manager (.getTextureManager minecraft)]
    (try
      (.bindForSetup texture-manager texture)
      (catch Exception _
        (.bind texture-manager texture)))))

(defn register-renderers
  "Register platform-agnostic renderers with the universal BlockEntityRenderer dispatcher
  
  Called during client initialization. Registers all block entity renderers
  by invoking their register! functions."
  []
  (log/info "Registering block renderers for Fabric 1.20.1...")
  (try
    (render/register-texture-binder! bind-texture-fabric!)

    ;; Register Matrix block renderer
    ;; The matrix-renderer/register! function:
    ;; - Registers TileMatrix with the core renderer dispatcher
    ;; - The universal BlockEntityRendererImpl class will dispatch to this renderer
    (matrix-renderer/register!)
    (solar-renderer/register!)
    (log/info "Matrix block renderer registered successfully")

    ;; Bind universal BER dispatcher to BlockEntityTypes
    (when-let [solar-type (fabric-mod/get-registered-block-entity-type "solar-gen")]
      (BlockEntityRenderers/register
        solar-type
        (reify BlockEntityRendererProvider
          (create [_ctx]
            (my_mod.fabric1201.client.render.BlockEntityRendererImpl.)))))
    
    (catch Exception e
      (log/error "Failed to register block renderers" e))))

(defn init-client
  "Initialize client-side systems for Fabric 1.20.1
  
  Called from Java ClientModInitializer. Registers all block entity renderers."
  []
  (log/info "Initializing Fabric 1.20.1 client-side systems")
  (register-renderers)
  (log/info "Fabric client initialization complete"))

(ns my-mod.fabric1201.client.init
  "Fabric 1.20.1 client-side initialization and registration"
  (:require [my-mod.util.log :as log]
            [my-mod.util.render :as render]
            [my-mod.registry.metadata :as registry-metadata]
            [my-mod.client.render.init :as render-init]
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

    (render-init/register-all-renderers!)

    ;; Bind universal BER to all scripted BlockEntityTypes (dispatcher uses block-id).
    ;; Register once per tile-id to support shared tiles across multiple blocks.
    (doseq [tile-id (registry-metadata/get-all-tile-ids)]
      (when-let [be-type (fabric-mod/get-registered-block-entity-type tile-id)]
        (BlockEntityRenderers/register
          be-type
          (reify BlockEntityRendererProvider
            (create [_ctx]
              (my_mod.fabric1201.client.render.BlockEntityRendererImpl.))))))
    
    (catch Exception e
      (log/error "Failed to register block renderers" e))))

(defn init-client
  "Initialize client-side systems for Fabric 1.20.1
  
  Called from Java ClientModInitializer. Registers all block entity renderers."
  []
  (log/info "Initializing Fabric 1.20.1 client-side systems")
  (register-renderers)
  (log/info "Fabric client initialization complete"))

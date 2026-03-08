(ns my-mod.forge1201.client.init
  "Forge 1.20.1 client-side initialization and registration"
  (:require [my-mod.util.log :as log]
            [my-mod.util.render :as render]
            [my-mod.registry.metadata :as registry-metadata]
            [my-mod.client.render.init :as render-init]
            [my-mod.forge1201.client.render.tesr-impl :as tesr-impl]
            [my-mod.forge1201.mod :as forge-mod])
  (:import [net.minecraft.client Minecraft]
           [net.minecraft.client.renderer.texture TextureManager]
           [net.minecraft.client.renderer.blockentity BlockEntityRenderers BlockEntityRendererProvider]
           [net.minecraft.world.level.block.entity BlockEntity]))

;; ============================================================================
;; Client Registration
;; ============================================================================

(defn- bind-texture-forge!
  "Bind a texture for rendering.
  
  Args:
    texture: ResourceLocation - texture to bind
  
  Uses direct method calls (no reflection) for better performance."
  [texture]
  (let [minecraft (Minecraft/getInstance)
        texture-manager (.getTextureManager minecraft)]
    (try
      ;; Prefer bindForSetup if available (clears previous bindings)
      (.bindForSetup texture-manager texture)
      (catch Exception _
        ;; Fallback to bind() if bindForSetup doesn't exist
        (.bind texture-manager texture)))))

(defn register-renderers
  "Register platform-agnostic renderers with the universal BlockEntityRenderer dispatcher
  
  Called during client setup phase. Registers all block entity renderers
  by invoking their register! functions."
  []
  (log/info "Registering block renderers for Forge 1.20.1...")
  (try
    (render/register-texture-binder! bind-texture-forge!)

    (render-init/register-all-renderers!)

    ;; Bind universal BER to all scripted BlockEntityTypes (dispatcher uses block-id).
    ;; Register once per tile-id to support shared tiles across multiple blocks.
    (doseq [tile-id (registry-metadata/get-all-tile-ids)]
      (when-let [be-type (forge-mod/get-registered-block-entity-type tile-id)]
        (BlockEntityRenderers/register
          be-type
          (reify BlockEntityRendererProvider
            (create [_this _ctx]
              (tesr-impl/new-renderer))))))
    
    (catch Exception e
      (log/error "Failed to register block renderers" e))))

(defn init-client
  "Initialize client-side systems for Forge 1.20.1.
  Called from within the FMLClientSetupEvent handler in mod.clj — do work directly,
  do not attempt to register new event listeners (the event is already firing)."
  []
  (log/info "Initializing Forge 1.20.1 client-side systems")
  (register-renderers)
  (log/info "Forge 1.20.1 client-side systems initialized"))

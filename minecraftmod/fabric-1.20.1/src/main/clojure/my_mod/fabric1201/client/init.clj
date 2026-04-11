(ns cn.li.fabric1201.client.init
  "Fabric 1.20.1 client-side initialization and registration"
  (:require [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.util.render :as render]
            [cn.li.mcmod.registry.metadata :as registry-metadata]
            [cn.li.mcmod.client.render.init :as render-init]
            [cn.li.mcmod.client.render.tesr-api :as tesr-api]
            [cn.li.fabric1201.client.render.block-entity-renderer-impl]
            [cn.li.fabric1201.mod :as fabric-mod])
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

    ;; Must run before register-all-renderers! so ac hooks can enqueue register! fns.
    (render-init/register-default-renderer-init-fns!)

    (render-init/register-all-renderers!)

    ;; Bind universal BER only to tile-ids that have at least one block with a
    ;; registered scripted renderer. Tiles using standard static models (e.g.
    ;; wireless nodes) have no renderer and must be skipped to avoid per-frame
    ;; "no renderer registered" log spam.
    (doseq [tile-id (registry-metadata/get-all-tile-ids)]
      (let [block-ids (or (seq (registry-metadata/get-tile-block-ids tile-id)) [tile-id])]
        (when (some tesr-api/get-scripted-tile-renderer block-ids)
          (when-let [be-type (fabric-mod/get-registered-block-entity-type tile-id)]
            (BlockEntityRenderers/register
              be-type
              (reify BlockEntityRendererProvider
                (create [_ctx]
                  (my_mod.fabric1201.client.render.BlockEntityRendererImpl.))))))))
    
    (catch Exception e
      (log/error "Failed to register block renderers" e))))

(defn init-client
  "Initialize client-side systems for Fabric 1.20.1
  
  Called from Java ClientModInitializer. Registers all block entity renderers."
  []
  (log/info "Initializing Fabric 1.20.1 client-side systems")
  (register-renderers)
  (log/info "Fabric client initialization complete"))

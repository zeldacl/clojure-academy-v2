(ns cn.li.fabric1201.client.init
  "Fabric 1.20.1 client-side initialization and registration"
  (:require [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.util.render :as render]
            [cn.li.mcmod.client.render.init :as render-init]
            [cn.li.mcmod.client.render.tesr-api :as tesr-api]
            [cn.li.mcmod.registry.metadata :as registry-metadata]
            [cn.li.fabric1201.mod :as mod])
  (:import [cn.li.fabric1201.shim FabricClientHelper]
           [cn.li.fabric1201.shim FabricClientHelper$RendererFactory]
           [cn.li.fabric1201.client.render BlockEntityRendererImpl]
           [net.minecraft.client Minecraft]))

(defn- bind-texture-fabric!
  "Bind a texture for rendering."
  [texture]
  (let [minecraft (Minecraft/getInstance)
        texture-manager (.getTextureManager minecraft)]
    (try
      (.bindForSetup texture-manager texture)
      (catch Exception _
        (.bind texture-manager texture)))))

(defn register-renderers
  "Register platform-agnostic renderers for Fabric."
  []
  (log/info "Registering block renderers for Fabric 1.20.1...")
  (try
    (render/register-texture-binder! bind-texture-fabric!)
    (render-init/register-default-renderer-init-fns!)
    (render-init/register-all-renderers!)
    (catch Exception e
      (log/error "Failed to register block renderers" e))))

(defn register-scripted-block-entity-renderers!
  "Attach a single universal BlockEntity renderer to all scripted tile types."
  []
  ;; Keep parity with Forge fallback behavior when renderer callbacks have not populated yet.
  (when (empty? @tesr-api/scripted-renderer-registry)
    (render-init/register-default-renderer-init-fns!)
    (render-init/register-all-renderers!))
  (doseq [tile-id (registry-metadata/get-all-tile-ids)]
    (when-let [be-type (mod/get-registered-block-entity-type tile-id)]
      (FabricClientHelper/registerBlockEntityRenderer
        be-type
        (reify FabricClientHelper$RendererFactory
          (create [_]
            (BlockEntityRendererImpl.))))
      (log/info (str "Fabric BER registered for tile-id " tile-id)))))

(defn init-client
  "Initialize client-side systems for Fabric 1.20.1."
  []
  (log/info "Initializing Fabric 1.20.1 client-side systems")
  (register-renderers)
  (register-scripted-block-entity-renderers!)
  (log/info "Fabric client initialization complete"))

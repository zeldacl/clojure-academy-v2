(ns cn.li.fabric1201.client.init
  "Fabric 1.20.1 client-side initialization and registration"
  (:require [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.util.render :as render]
            [cn.li.mcmod.client.render.init :as render-init])
  (:import [net.minecraft.client Minecraft]))

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

(defn init-client
  "Initialize client-side systems for Fabric 1.20.1."
  []
  (log/info "Initializing Fabric 1.20.1 client-side systems")
  (register-renderers)
  (log/info "Fabric client initialization complete"))

(ns cn.li.fabric1201.client.init
  "Fabric 1.20.1 client-side initialization and registration"
  (:require [cn.li.mc1201.client.i18n :as i18n]
            [cn.li.mc1201.client.render.pose :as pose-impl]
            [cn.li.mc1201.client.render.buffer :as buffer-impl]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.util.render :as render]
            [cn.li.mcmod.client.render.pose :as pose]
            [cn.li.mcmod.client.render.buffer :as buffer]
            [cn.li.mcmod.client.render.init :as render-init]
            [cn.li.mcmod.client.render.tesr-api :as tesr-api]
            [cn.li.mcmod.protocol.metadata :as registry-metadata]
            [cn.li.fabric1201.client.overlay-renderer :as overlay-renderer]
            [cn.li.fabric1201.client.hand-effect-renderer :as hand-effect-renderer]
            [cn.li.fabric1201.client.level-effect-renderer :as level-effect-renderer]
            [cn.li.fabric1201.client.runtime-bridge :as runtime-bridge]
            [cn.li.fabric1201.mod :as mod])
  (:import [cn.li.fabric1201.client FabricClientRenderSetup]
           [cn.li.fabric1201.shim FabricClientHelper]
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

(defn- init-render-bindings!
  []
  (log/info "Binding Fabric client-side rendering implementations...")

  (alter-var-root #'pose/*y-rotation-fn* (constantly pose-impl/rotate-y))
  (alter-var-root #'pose/*x-rotation-fn* (constantly pose-impl/rotate-x))
  (alter-var-root #'pose/*z-rotation-fn* (constantly pose-impl/rotate-z))
  (alter-var-root #'pose/*push-pose-fn* (constantly pose-impl/push-pose))
  (alter-var-root #'pose/*pop-pose-fn* (constantly pose-impl/pop-pose))
  (alter-var-root #'pose/*translate-fn* (constantly pose-impl/translate))
  (alter-var-root #'pose/*scale-fn* (constantly pose-impl/scale))
  (alter-var-root #'pose/*get-matrix-fn* (constantly pose-impl/get-pose-matrix))

  (alter-var-root #'buffer/*submit-vertex-fn* (constantly pose-impl/submit-vertex))
  (alter-var-root #'buffer/*triangle-vertex-order* (constantly [0 1 2 2]))
  (alter-var-root #'buffer/*solid-buffer-fn* (constantly buffer-impl/get-solid-buffer))
  (alter-var-root #'buffer/*translucent-buffer-fn* (constantly buffer-impl/get-translucent-buffer))
  (alter-var-root #'buffer/*cutout-no-cull-buffer-fn* (constantly buffer-impl/get-cutout-no-cull-buffer))

  (log/info "Fabric client-side rendering bindings complete"))

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
  (init-render-bindings!)
  (i18n/install-client-i18n!)
  (register-renderers)
  (FabricClientRenderSetup/registerEntityRenderers)
  (register-scripted-block-entity-renderers!)
  (overlay-renderer/init!)
  (hand-effect-renderer/init!)
  (level-effect-renderer/init!)
  (runtime-bridge/init!)
  (log/info "Fabric client initialization complete"))

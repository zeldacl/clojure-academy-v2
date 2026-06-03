(ns cn.li.fabric1201.client.init
  "Fabric 1.20.1 client-side initialization and registration"
  (:require [cn.li.mc1201.client.i18n :as i18n]
            [cn.li.mc1201.client.render.pose :as pose-impl]
            [cn.li.mc1201.client.render.buffer :as buffer-impl]
            [cn.li.mc1201.client.screen.host :as screen-host]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
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
    (.bindForSetup texture-manager texture)))

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

  (pose/install-pose-ops!
   {:y-rotation pose-impl/rotate-y
    :x-rotation pose-impl/rotate-x
    :z-rotation pose-impl/rotate-z
    :push-pose pose-impl/push-pose
    :pop-pose pose-impl/pop-pose
    :translate pose-impl/translate
    :scale pose-impl/scale
    :get-matrix pose-impl/get-pose-matrix}
   "fabric-client")

  (buffer/install-render-buffer-ops!
   {:solid buffer-impl/get-solid-buffer
    :translucent buffer-impl/get-translucent-buffer
    :cutout-no-cull buffer-impl/get-cutout-no-cull-buffer
    :submit-vertex pose-impl/submit-vertex
    :triangle-vertex-order [0 1 2 2]}
   "fabric-client")

  (log/info "Fabric client-side rendering bindings complete"))

(defn register-scripted-block-entity-renderers!
  "Attach a single universal BlockEntity renderer to all scripted tile types."
  []
  ;; Keep parity with Forge fallback behavior when renderer callbacks have not populated yet.
  (when (empty? (tesr-api/scripted-renderers-snapshot))
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

(defn- init-content-client-bridge!
  []
  (client-bridge/install-client-bridge!
    {:open-screen (fn [screen-key payload]
                    (screen-host/open-managed-screen! screen-key payload))
     :slot-key-down runtime-bridge/on-slot-key-down!
     :slot-key-tick runtime-bridge/on-slot-key-tick!
     :slot-key-up runtime-bridge/on-slot-key-up!
      :slot-key-abort runtime-bridge/on-slot-key-abort!
     :movement-key-down runtime-bridge/on-movement-key-down!
     :movement-key-tick runtime-bridge/on-movement-key-tick!
     :movement-key-up runtime-bridge/on-movement-key-up!}))

(defn init-client
  "Initialize client-side systems for Fabric 1.20.1."
  []
  (log/info "Initializing Fabric 1.20.1 client-side systems")
  (init-render-bindings!)
  (init-content-client-bridge!)
  (i18n/install-client-i18n!)
  (register-renderers)
  (FabricClientRenderSetup/registerEntityRenderers)
  (register-scripted-block-entity-renderers!)
  (overlay-renderer/init!)
  (hand-effect-renderer/init!)
  (level-effect-renderer/init!)
  (screen-host/init!)
  (runtime-bridge/init!)
  (log/info "Fabric client initialization complete"))

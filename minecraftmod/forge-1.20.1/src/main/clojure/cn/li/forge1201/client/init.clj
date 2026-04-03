(ns cn.li.forge1201.client.init
  "CLIENT-ONLY: Client-side initialization for Forge 1.20.1.

  This namespace must be loaded via side-checked requiring-resolve from the
  platform layer. It contains client-only initialization code including
  renderer registration and texture binding."
  (:require [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.util.render :as render]
            [cn.li.mcmod.registry.metadata :as registry-metadata]
            [cn.li.mcmod.client.render.init :as render-init]
            [cn.li.mcmod.client.render.tesr-api :as tesr-api]
            [cn.li.forge1201.client.ability-runtime :as ability-runtime]
            [cn.li.forge1201.client.ability-input :as ability-input]
            [cn.li.forge1201.client.ability-hud :as ability-hud]
            [cn.li.forge1201.client.ability-hud-bridge :as ability-hud-bridge]
            [cn.li.forge1201.client.ability-screen-bridge :as ability-screen-bridge]
            [cn.li.forge1201.client.terminal-screen-bridge :as terminal-screen-bridge]
            [cn.li.forge1201.client.effects.particle-bridge :as particle-bridge]
            [cn.li.forge1201.client.effects.sound-bridge :as sound-bridge]
            [cn.li.forge1201.client.ability-gui :as ability-gui]
            [cn.li.forge1201.client.render.tesr-impl :as tesr-impl]
            [cn.li.forge1201.mod :as forge-mod]
            [cn.li.forge1201.client.pose-impl :as pose-impl]
            [cn.li.forge1201.client.render-buffer-impl :as buffer-impl]
            [cn.li.mcmod.client.render.pose :as pose]
            [cn.li.mcmod.client.render.buffer :as buffer])
  (:import [cn.li.forge1201.shim ForgeClientHelper ForgeClientHelper$RendererFactory]))

;; ============================================================================
;; Client Registration
;; ============================================================================

(defn- bind-texture-forge!
  "Bind a texture for rendering.
  
  Args:
    texture: ResourceLocation - texture to bind
  
  Uses direct method calls (no reflection) for better performance."
  [texture]
  (ForgeClientHelper/bindTextureForSetup texture))

(defn register-renderers
  "Register platform-agnostic renderers with the universal BlockEntityRenderer dispatcher
  
  Called during client setup phase. Registers all block entity renderers
  by invoking their register! functions."
  []
  (log/info "Registering block renderers for Forge 1.20.1...")
  (try
    (render/register-texture-binder! bind-texture-forge!)

    ;; Inject core renderer registration callbacks into mcmod.
    (render-init/register-default-renderer-init-fns!)

    (render-init/register-all-renderers!)

    ;; Bind universal BER only to tile-ids that have at least one block with a
    ;; registered scripted renderer. Tiles using standard static models (e.g.
    ;; wireless nodes) have no renderer and must be skipped to avoid per-frame
    ;; "no renderer registered" log spam.
    (doseq [tile-id (registry-metadata/get-all-tile-ids)]
      (let [block-ids (or (seq (registry-metadata/get-tile-block-ids tile-id)) [tile-id])]
        (when (some tesr-api/get-scripted-tile-renderer block-ids)
          (when-let [be-type (forge-mod/get-registered-block-entity-type tile-id)]
            (ForgeClientHelper/registerBlockEntityRenderer
              be-type
              (reify ForgeClientHelper$RendererFactory
                (create [_this]
                  (tesr-impl/new-renderer))))))))
    
    (catch Exception e
      (log/error "Failed to register block renderers" (.printStackTrace e)))))

(defn- init-render-bindings!
  "Bind client-side rendering functions to mcmod's dynamic vars.

  This must only be called on the client side."
  []
  (log/info "Binding client-side rendering implementations...")

  ;; Bind pose stack implementations
  (alter-var-root #'pose/*y-rotation-fn* (constantly pose-impl/rotate-y))
  (alter-var-root #'pose/*push-pose-fn* (constantly pose-impl/push-pose))
  (alter-var-root #'pose/*pop-pose-fn* (constantly pose-impl/pop-pose))
  (alter-var-root #'pose/*translate-fn* (constantly pose-impl/translate))
  (alter-var-root #'pose/*get-matrix-fn* (constantly pose-impl/get-pose-matrix))

  ;; Bind vertex consumer implementation
  (alter-var-root #'buffer/*submit-vertex-fn* (constantly pose-impl/submit-vertex))

  ;; Bind render buffer selectors
  (alter-var-root #'buffer/*solid-buffer-fn* (constantly buffer-impl/get-solid-buffer))
  (alter-var-root #'buffer/*translucent-buffer-fn* (constantly buffer-impl/get-translucent-buffer))
  (alter-var-root #'buffer/*cutout-no-cull-buffer-fn* (constantly buffer-impl/get-cutout-no-cull-buffer))

  (log/info "Client-side rendering bindings complete"))

(defn init-client
  "Initialize client-side systems for Forge 1.20.1.
  Called from within the FMLClientSetupEvent handler in mod.clj — do work directly,
  do not attempt to register new event listeners (the event is already firing)."
  []
  (log/info "Initializing Forge 1.20.1 client-side systems")

  ;; Bind client-side rendering implementations first
  (init-render-bindings!)

  ;; Then register renderers
  (register-renderers)

  ;; Ability client systems
  (ability-input/init!)
  (ability-runtime/init!)
  (ability-hud/init!)
  (ability-hud-bridge/init!)
  (ability-screen-bridge/init!)
  (terminal-screen-bridge/init!)
  (particle-bridge/init!)
  (sound-bridge/init!)
  (ability-gui/init!)

  (log/info "Forge 1.20.1 client-side systems initialized"))

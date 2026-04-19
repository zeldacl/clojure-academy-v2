(ns cn.li.forge1201.client.init
  "CLIENT-ONLY: Client-side initialization for Forge 1.20.1.

  This namespace must be loaded via side-checked requiring-resolve from the
  platform layer. It contains client-only initialization code including
  renderer registration and texture binding."
  (:require [cn.li.ac.client.platform-bridge :as ac-client-bridge]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.util.render :as render]
            [cn.li.mcmod.registry.metadata :as registry-metadata]
            [cn.li.mcmod.client.render.init :as render-init]
            [cn.li.mcmod.client.render.tesr-api :as tesr-api]
            [cn.li.forge1201.client.runtime-bridge :as runtime-bridge]
            [cn.li.forge1201.client.key-input :as key-input]
            [cn.li.forge1201.client.overlay-renderer :as overlay-renderer]
            [cn.li.forge1201.client.screen-host :as screen-host]
            [cn.li.forge1201.client.terminal-screen-bridge :as terminal-screen-bridge]
            [cn.li.forge1201.client.effects.particle-bridge :as particle-bridge]
            [cn.li.forge1201.client.effects.sound-bridge :as sound-bridge]
            [cn.li.forge1201.client.hand-effect-renderer :as hand-effect-renderer]
            [cn.li.forge1201.client.level-effect-renderer :as level-effect-renderer]
            [cn.li.forge1201.client.request-bridge :as request-bridge]
            [cn.li.forge1201.client.render.tesr-impl :as tesr-impl]
            [cn.li.forge1201.client.pose-impl :as pose-impl]
            [cn.li.forge1201.client.render-buffer-impl :as buffer-impl]
            [cn.li.forge1201.client.energy-item-model-properties :as energy-item-model-properties]
            [cn.li.mcmod.client.render.pose :as pose]
            [cn.li.mcmod.client.render.buffer :as buffer])
  (:import [cn.li.forge1201.shim ForgeClientHelper]
           [net.minecraftforge.client.event EntityRenderersEvent$RegisterRenderers]
           [net.minecraftforge.client.event RegisterKeyMappingsEvent]
           [net.minecraft.client KeyMapping]
           [net.minecraft.client.renderer.blockentity BlockEntityRendererProvider]))

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

    ;; Scripted BER: `ModClientRenderSetup` (Java @Mod.EventBusSubscriber) on mod bus.
    
    (catch Exception e
      (log/error "Failed to register block renderers" (.printStackTrace e)))))

(defn- register-fluid-render-layers!
  []
  (when-let [get-fluid-source (requiring-resolve 'cn.li.forge1201.mod/get-registered-fluid-source)]
    (when-let [get-fluid-flowing (requiring-resolve 'cn.li.forge1201.mod/get-registered-fluid-flowing)]
      (doseq [fluid-id (registry-metadata/get-all-fluid-ids)]
        (let [fluid-spec (registry-metadata/get-fluid-spec fluid-id)
              translucent? (true? (get-in fluid-spec [:rendering :is-translucent]))]
          (when translucent?
            (when-let [source (get-fluid-source fluid-id)]
              (when-let [flowing (get-fluid-flowing fluid-id)]
                (ForgeClientHelper/setFluidRenderLayerTranslucent source flowing)))))))))

(defn- init-render-bindings!
  "Bind client-side rendering functions to mcmod's dynamic vars.

  This must only be called on the client side."
  []
  (log/info "Binding client-side rendering implementations...")

  ;; Bind pose stack implementations
  (alter-var-root #'pose/*y-rotation-fn* (constantly pose-impl/rotate-y))
  (alter-var-root #'pose/*x-rotation-fn* (constantly pose-impl/rotate-x))
  (alter-var-root #'pose/*z-rotation-fn* (constantly pose-impl/rotate-z))
  (alter-var-root #'pose/*push-pose-fn* (constantly pose-impl/push-pose))
  (alter-var-root #'pose/*pop-pose-fn* (constantly pose-impl/pop-pose))
  (alter-var-root #'pose/*translate-fn* (constantly pose-impl/translate))
  (alter-var-root #'pose/*scale-fn* (constantly pose-impl/scale))
  (alter-var-root #'pose/*get-matrix-fn* (constantly pose-impl/get-pose-matrix))

  ;; Bind vertex consumer implementation
  (alter-var-root #'buffer/*submit-vertex-fn* (constantly pose-impl/submit-vertex))
  (alter-var-root #'buffer/*triangle-vertex-order* (constantly [0 1 2 2]))

  ;; Bind render buffer selectors
  (alter-var-root #'buffer/*solid-buffer-fn* (constantly buffer-impl/get-solid-buffer))
  (alter-var-root #'buffer/*translucent-buffer-fn* (constantly buffer-impl/get-translucent-buffer))
  (alter-var-root #'buffer/*cutout-no-cull-buffer-fn* (constantly buffer-impl/get-cutout-no-cull-buffer))

  (log/info "Client-side rendering bindings complete"))

(defn- ensure-client-render-platform-for-ber!
  "Forge can fire RegisterRenderers before FMLClientSetup enqueueWork. Without pose
  and buffer roots, TESR runs with nil fns (skips transforms) or throws from
  get-solid-buffer."
  []
  (render/register-texture-binder! bind-texture-forge!)
  (init-render-bindings!))

(defn register-scripted-block-entity-renderers!
  "Bind universal BER for scripted tiles. Must run under
  `EntityRenderersEvent.RegisterRenderers` (Forge 1.20.1 mod bus), not
  `BlockEntityRenderers.register` from client setup."
  [^EntityRenderersEvent$RegisterRenderers evt]
  (ensure-client-render-platform-for-ber!)
  (log/info "RegisterRenderers - attaching scripted block entity renderers")
  ;; `FMLClientSetupEvent` work is often enqueued; this event can run first. Populate
  ;; scripted TESR callbacks only when still empty (no duplicate when order is normal).
  (when (empty? @tesr-api/scripted-renderer-registry)
    (log/info "RegisterRenderers - scripted registry empty; running renderer init (event-order fallback)")
    (render-init/register-default-renderer-init-fns!)
    (render-init/register-all-renderers!))
  (doseq [tile-id (registry-metadata/get-all-tile-ids)]
    (let [block-ids (or (seq (registry-metadata/get-tile-block-ids tile-id)) [tile-id])]
      (when (some tesr-api/get-scripted-tile-renderer block-ids)
        (when-let [get-be-type (requiring-resolve 'cn.li.forge1201.mod/get-registered-block-entity-type)]
          (when-let [be-type (get-be-type tile-id)]
            (.registerBlockEntityRenderer
              evt
              be-type
              (reify BlockEntityRendererProvider
                (create [_ _ctx]
                  (tesr-impl/new-renderer))))
            (log/info (str "  BER registered for tile-id " tile-id))))))))

(defn- init-ac-client-bridge!
  []
  (ac-client-bridge/install-client-bridge!
    {:open-skill-tree-screen screen-host/open-skill-tree-screen!
     :open-preset-editor-screen screen-host/open-preset-editor-screen!
     :open-location-teleport-screen screen-host/open-location-teleport-screen!
     :open-terminal-screen terminal-screen-bridge/open-terminal-screen!
     :open-simple-gui terminal-screen-bridge/open-simple-gui!
     :local-player-item-id runtime-bridge/local-player-item-id
     :local-player-pos runtime-bridge/local-player-pos
     :local-player-eye-pos runtime-bridge/local-player-eye-pos
     :local-player-look-end runtime-bridge/local-player-look-end
     :clear-client-activated-overlay runtime-bridge/clear-client-activated-overlay!
     :play-intensify-local-effect runtime-bridge/play-intensify-local-effect!}))

(defn register-key-mappings!
  "Register all ability KeyMapping instances to Forge input system."
  [^RegisterKeyMappingsEvent event]
  ;; Ensure mappings are created even if this event fires before client setup enqueueWork.
  (key-input/register-keybinds!)
  (let [all-keys (concat (key-input/get-skill-keys)
                         (key-input/get-gui-keys))]
    (doseq [^KeyMapping key all-keys]
      (.register event key))
    (log/info "Registered ability key mappings:" (count all-keys))))

(defn init-client
  "Initialize client-side systems for Forge 1.20.1.
  Called from within the FMLClientSetupEvent handler in mod.clj — do work directly,
  do not attempt to register new event listeners (the event is already firing)."
  []
  (log/info "Initializing Forge 1.20.1 client-side systems")

  ;; Bind client-side rendering implementations first
  (init-render-bindings!)
  (init-ac-client-bridge!)

  ;; Then register renderers
  (register-renderers)
  (register-fluid-render-layers!)

  ;; Ability client systems
  (key-input/init!)
  (runtime-bridge/init!)
  (overlay-renderer/init!)
  (screen-host/init!)
  (terminal-screen-bridge/init!)
  (particle-bridge/init!)
  (sound-bridge/init!)
  (hand-effect-renderer/init!)
  (level-effect-renderer/init!)
  (request-bridge/init!)

  (energy-item-model-properties/register!)

  (log/info "Forge 1.20.1 client-side systems initialized"))

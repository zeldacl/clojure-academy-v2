(ns cn.li.forge1201.client.init
  "CLIENT-ONLY: Client-side initialization for Forge 1.20.1.

  This namespace must be loaded via side-checked requiring-resolve from the
  platform layer. It contains client-only initialization code including
  renderer registration and texture binding."
  (:require [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.client.content-actions :as content-actions]
            [cn.li.mcmod.platform.tutorial-events :as tutorial-platform]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.util.render :as render]
            [cn.li.mcmod.protocol.metadata :as registry-metadata]
            [cn.li.mcmod.client.render.init :as render-init]
            [cn.li.mcmod.client.render.tesr-api :as tesr-api]
            [cn.li.mc1201.client.gl-ops :as gl-ops]
            [cn.li.mc1201.client.effects.particle :as particle]
            [cn.li.mc1201.client.effects.sound :as sound]
            [cn.li.mc1201.client.render.pose :as pose-impl]
            [cn.li.mc1201.client.render.buffer :as buffer-impl]
            [cn.li.mc1201.client.screen.host :as screen-host]
            [cn.li.mc1201.client.request.bridge :as request-bridge]
            [cn.li.mc1201.client.font.msdf-setup :as msdf-setup]
            [cn.li.mc1201.gui.cgui.draw-ops-host :as draw-ops-host]
            [cn.li.forge1201.client.runtime-bridge :as runtime-bridge]
            [cn.li.forge1201.client.key-input :as key-input]
            [cn.li.forge1201.client.overlay-renderer :as overlay-renderer]
            [cn.li.forge1201.client.cgui-screen-bridge :as cgui-screen-bridge]
            [cn.li.forge1201.client.hand-effect-renderer :as hand-effect-renderer]
            [cn.li.forge1201.client.level-effect-renderer :as level-effect-renderer]
            [cn.li.forge1201.client.render.tesr-impl :as tesr-impl]
            [cn.li.forge1201.client.energy-item-model-properties :as energy-item-model-properties]
            [cn.li.mcmod.client.render.pose :as pose]
            [cn.li.mcmod.client.render.buffer :as buffer]
            [cn.li.forge1201.registry.state :as registry-state])
  (:import [cn.li.forge1201.shim ForgeClientHelper]
           [net.minecraft.client Minecraft]
           [net.minecraft.client.player LocalPlayer]
           [net.minecraft.network.chat Component]
           [net.minecraftforge.common MinecraftForge]
           [net.minecraftforge.client.event EntityRenderersEvent$RegisterRenderers]
           [net.minecraftforge.client.event RegisterKeyMappingsEvent]
           [net.minecraftforge.event TickEvent$ClientTickEvent TickEvent$Phase]
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
    (render/register-gl-ops! (gl-ops/ops-map))

    ;; Inject core renderer registration callbacks into mcmod.
    (render-init/register-default-renderer-init-fns!)

    (render-init/register-all-renderers!)

    ;; Scripted BER: `ModClientRenderSetup` (Java @Mod.EventBusSubscriber) on mod bus.
    
    (catch Exception e
      (log/error "Failed to register block renderers" (.printStackTrace e)))))

(defn- register-fluid-render-layers!
  []
  (doseq [fluid-id (registry-metadata/get-all-fluid-ids)]
    (let [fluid-spec (registry-metadata/get-fluid-spec fluid-id)
          translucent? (true? (get-in fluid-spec [:rendering :is-translucent]))]
      (when translucent?
        (when-let [source (registry-state/get-registered-fluid-source fluid-id)]
          (when-let [flowing (registry-state/get-registered-fluid-flowing fluid-id)]
            (ForgeClientHelper/setFluidRenderLayerTranslucent source flowing)))))))

(defn- init-render-bindings!
  "Bind client-side rendering functions to mcmod's dynamic vars.

  This must only be called on the client side."
  []
  (log/info "Binding client-side rendering implementations...")

  (pose/install-pose-ops!
   {:y-rotation pose-impl/rotate-y
    :x-rotation pose-impl/rotate-x
    :z-rotation pose-impl/rotate-z
    :axis-rotation pose-impl/rotate-axis
    :push-pose pose-impl/push-pose
    :pop-pose pose-impl/pop-pose
    :translate pose-impl/translate
    :scale pose-impl/scale
    :get-matrix pose-impl/get-pose-matrix}
   "forge-client")

  (buffer/install-render-buffer-ops!
   {:solid buffer-impl/get-solid-buffer
    :translucent buffer-impl/get-translucent-buffer
    :cutout-no-cull buffer-impl/get-cutout-no-cull-buffer
    :submit-vertex pose-impl/submit-vertex
    :triangle-vertex-order [0 1 2 2]}
   "forge-client")

  (log/info "Client-side rendering bindings complete"))

(defn- ensure-client-render-platform-for-ber!
  "Forge can fire RegisterRenderers before FMLClientSetup enqueueWork. Without pose
  and buffer roots, TESR runs with nil fns (skips transforms) or throws from
  get-solid-buffer."
  []
  (render/register-texture-binder! bind-texture-forge!)
  (render/register-gl-ops! (gl-ops/ops-map))
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
  (when (empty? (tesr-api/scripted-renderers-snapshot))
    (log/info "RegisterRenderers - scripted registry empty; running renderer init (event-order fallback)")
    (render-init/register-default-renderer-init-fns!)
    (render-init/register-all-renderers!))
  (doseq [tile-id (registry-metadata/get-all-tile-ids)]
    (let [block-ids (or (seq (registry-metadata/get-tile-block-ids tile-id)) [tile-id])]
      (when (some tesr-api/get-scripted-tile-renderer block-ids)
        (when-let [be-type (registry-state/get-registered-block-entity-type tile-id)]
          (.registerBlockEntityRenderer
            evt
            be-type
            (reify BlockEntityRendererProvider
              (create [_ _ctx]
                (tesr-impl/new-renderer))))
          (log/info (str "  BER registered for tile-id " tile-id)))))))

(defn- init-content-client-bridge!
  []
  (client-bridge/install-client-bridge!
    {:open-screen (fn [screen-key payload]
                    (screen-host/open-managed-screen! screen-key payload))
    :open-simple-gui cgui-screen-bridge/open-simple-gui!
     :slot-key-down runtime-bridge/on-slot-key-down!
     :slot-key-tick runtime-bridge/on-slot-key-tick!
     :slot-key-up runtime-bridge/on-slot-key-up!
      :slot-key-abort runtime-bridge/on-slot-key-abort!
     :movement-key-down runtime-bridge/on-movement-key-down!
     :movement-key-tick runtime-bridge/on-movement-key-tick!
     :movement-key-up runtime-bridge/on-movement-key-up!
     :local-player-item-id runtime-bridge/local-player-item-id
     :local-player-pos runtime-bridge/local-player-pos
     :local-player-eye-pos runtime-bridge/local-player-eye-pos
     :local-player-look-end runtime-bridge/local-player-look-end
     :clear-client-activated-overlay runtime-bridge/clear-client-activated-overlay!
     :run-client-effect (fn [effect-key payload]
                          (case effect-key
                            :mcmod/spawn-local-scripted-effect
                            (runtime-bridge/spawn-local-scripted-effect! (:effect-id payload))

                            :mcmod/remove-local-scripted-effect
                            (runtime-bridge/remove-local-scripted-effect! (:entity-uuid payload))

                            (log/debug "Unhandled client effect key" effect-key)))
	     :get-client-player #(.player (Minecraft/getInstance))
	     :screen-active? #(some? (.screen (Minecraft/getInstance)))
	     :close-screen! #(.setScreen (Minecraft/getInstance) nil)
       :get-mouse-pos (fn []
                        (let [^net.minecraft.client.Minecraft mc (Minecraft/getInstance)
                              mh (.mouseHandler mc)]
                          [(double (.xpos mh)) (double (.ypos mh))]))
       :send-system-message! (fn [player translatable-key & args]
                                (let [^LocalPlayer player player]
                                  (.sendSystemMessage player
                                    (Component/translatable translatable-key (into-array Object args)))))
       :game-time-ms (fn []
                       (if-let [^net.minecraft.client.Minecraft mc (Minecraft/getInstance)]
                         (if-let [level (.level mc)]
                           ;; Include partial tick for sub-tick smoothness
                           ;; (upstream GameTimer.getTime = worldTime + partialTick)
                           (long (+ (* (.getGameTime level) 50)
                                    (* (double (.getFrameTime mc)) 50.0)))
                           (System/currentTimeMillis))
                         (System/currentTimeMillis)))
       :font-width (fn [^String text]
                     (let [^net.minecraft.client.Minecraft mc (Minecraft/getInstance)]
                       (.width (.-font mc) text)))
       :resolve-shader (fn [shader-name]
                         (case shader-name
                           :skill-progbar (cn.li.forge1201.client.render.ModShaders/getSkillProgbarShader)
                           :mono (cn.li.forge1201.client.render.ModShaders/getMonoShader)
                           :alpha-discard (cn.li.forge1201.client.render.ModShaders/getAlphaDiscardShader)
                           nil))
       :get-window-size (fn []
                         (let [^net.minecraft.client.Minecraft mc (Minecraft/getInstance)]
                           [(.getGuiScaledWidth mc) (.getGuiScaledHeight mc)]))
       :draw-ops-host! (fn [parent ops-fn]
                         (draw-ops-host/draw-ops-host! parent ops-fn))}))

(defn register-key-mappings!
  "Register all runtime KeyMapping instances to Forge input system."
  [^RegisterKeyMappingsEvent event]
  ;; Ensure mappings are created even if this event fires before client setup enqueueWork.
  (key-input/register-keybinds!)
  (let [all-keys (concat (key-input/get-slot-keys)
                         (key-input/get-screen-keys))]
    (doseq [^KeyMapping key all-keys]
      (.register event key))
    (log/info "Registered runtime key mappings:" (count all-keys))))

(defn init-client
  "Initialize client-side systems for Forge 1.20.1.
  Called from within the FMLClientSetupEvent handler in mod.clj — do work directly,
  do not attempt to register new event listeners (the event is already firing)."
  []
  (log/info "Initializing Forge 1.20.1 client-side systems")

  ;; Bind client-side rendering implementations first
  (init-render-bindings!)
  (init-content-client-bridge!)

  ;; Then register renderers
  (register-renderers)
  (register-fluid-render-layers!)

  ;; Runtime client systems
  (key-input/init!)
  (runtime-bridge/init!)
  (overlay-renderer/init!)
  (screen-host/init!)
  (cgui-screen-bridge/init!)
  (msdf-setup/init!)
  (particle/init!)
  (sound/init!)
  (hand-effect-renderer/init!)
  (level-effect-renderer/init!)
  (request-bridge/init!)

  (energy-item-model-properties/register!)

  ;; Register tutorial activation hook (server-side only, for logging)
  (tutorial-platform/register-tutorial-activated-hook!
   (fn [player-uuid tut-id]
     (log/info "Tutorial activated:" (name tut-id) "for player" player-uuid)))

  ;; Register client tick handler for periodic tutorial state sync.
  ;; Keeps client state current so activation notifications appear
  ;; without requiring the tutorial GUI to be open (matching upstream
  ;; AcademyCraft NotifyUI real-time behavior).
  (try
    (.addListener (MinecraftForge/EVENT_BUS)
                  net.minecraftforge.eventbus.api.EventPriority/NORMAL
                  false
                  net.minecraftforge.event.TickEvent$ClientTickEvent
                  (reify java.util.function.Consumer
                    (accept [_ evt]
                      (let [^TickEvent$ClientTickEvent evt evt]
                        (when (= (.phase evt) TickEvent$Phase/END)
                          (content-actions/tick-tutorial-background-sync!))))))
    (catch Throwable _
      (log/warn "Failed to register tutorial background sync")))

  (log/info "Forge 1.20.1 client-side systems initialized"))

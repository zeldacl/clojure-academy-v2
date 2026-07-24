(ns cn.li.forge1201.client.init
  "CLIENT-ONLY: Client-side initialization for Forge 1.20.1.

  This namespace must be loaded via the side-checked client resolver from the
  platform layer. It contains client-only initialization code including
  renderer registration and texture binding."
  (:require [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.client.content-actions :as content-actions]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.client.ui.registry :as widget-registry]
            [cn.li.mcmod.spi.key-scheme-provider :as key-scheme-spi]
            [cn.li.mcmod.lifecycle :as lifecycle]
            [cn.li.mcmod.util.render :as render]
            [cn.li.mcmod.hooks.tutorial-events :as tutorial-hooks]
            [cn.li.mcmod.protocol.metadata :as registry-metadata]
            [cn.li.mcmod.client.render.init :as render-init]
            [cn.li.mcmod.client.render.tesr-api :as tesr-api]
            [cn.li.mc1201.client.audio.media-playback :as media-playback-bridge]
            [cn.li.mc1201.client.effects.particle :as particle]
            [cn.li.mc1201.client.effects.sound :as sound]
            [cn.li.mc1201.client.render.pose :as pose-impl]
            [cn.li.mc1201.client.render.buffer :as buffer-impl]
            [cn.li.mc1201.client.request.bridge :as request-bridge]
            [cn.li.mc1201.client.font.msdf-setup :as msdf-setup]
            [cn.li.mc1201.client.i18n :as i18n]
            [cn.li.mc1201.gui.cgui.font :as cgui-font]
            [cn.li.mc1201.client.session :as mc-session]
            [cn.li.mc1201.key-scheme-provider-core :as key-scheme-core]
            [cn.li.ac.content.ability-client :as ability-client]
            [cn.li.forge1201.client.runtime-bridge :as runtime-bridge]
            [cn.li.forge1201.client.key-mapping-adapter :as key-mapping-adapter]
            [cn.li.forge1201.client.keyboard-event-handler :as keyboard-event-handler]
            [cn.li.forge1201.client.overlay-renderer :as overlay-renderer]
            [cn.li.mc1201.client.overlay.state :as overlay-state]
            [cn.li.mc1201.gui.reactive.host :as reactive-host]
            [cn.li.mc1201.gui.reactive.terminal-render :as terminal-render]
            [cn.li.forge1201.adapter.gui-registry :as gui-registry]
            [cn.li.forge1201.gui.network :as gui-network]
            [cn.li.forge1201.gui.screen-impl :as gui-screen-impl]
            [cn.li.forge1201.runtime.owner :as runtime-owner]
            [cn.li.forge1201.client.hand-effect-renderer :as hand-effect-renderer]
            [cn.li.forge1201.client.level-effect-renderer :as level-effect-renderer]
            [cn.li.forge1201.client.render.tesr-impl :as tesr-impl]
            [cn.li.forge1201.client.energy-item-model-properties :as energy-item-model-properties]
            [cn.li.mcmod.client.render.pose :as pose]
            [cn.li.mcmod.client.render.buffer :as buffer]
            [cn.li.forge1201.registry.state :as registry-state]
            [cn.li.forge1201.integration.recipe-query :as recipe-query])
  (:import [cn.li.forge1201.shim ForgeClientHelper]
           [cn.li.forge1201.mixin GuiGraphicsInvoker]
           [net.minecraft.client Minecraft]
           [net.minecraft.client.player LocalPlayer]
           [net.minecraft.client.multiplayer ClientLevel]
           [net.minecraft.network.chat Component]
           [net.minecraft.world.entity.player Player]
           [net.minecraftforge.common MinecraftForge]
           [net.minecraftforge.client.event EntityRenderersEvent$RegisterRenderers]
           [net.minecraftforge.client.event RegisterKeyMappingsEvent]
           [net.minecraftforge.event TickEvent$ClientTickEvent TickEvent$Phase]
           [net.minecraft.client KeyMapping]
           [cn.li.forge1201.client.render ScriptedBlockEntityBerProvider]
           [com.mojang.blaze3d.platform Window]))

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
            (ScriptedBlockEntityBerProvider.))
          (log/info (str "  BER registered for tile-id " tile-id)))))))

(defn- open-screen-dispatcher
  "Dispatch open-screen to a registered reactive widget factory."
  [arg payload]
  (when (keyword? arg)
    (if-let [widget (widget-registry/create-widget arg payload)]
      (reactive-host/open-reactive-screen!
        (:runtime widget) (:title widget "Screen") {:on-close (:on-close widget)})
      (throw (ex-info "No reactive screen widget registered"
                      {:screen-key arg
                       :payload payload})))))

(defn- open-reactive-screen-handler [& args]
  (apply reactive-host/open-reactive-screen! args))

(defn- init-content-client-bridge!
  []
  ;; MERGE, not install: install-client-bridge! REPLACES the whole map and
  ;; wipes adapters content modules registered earlier during modloading —
  ;; ac's :reactive-overlay-build/update were lost here, leaving the reactive
  ;; HUD permanently unbuilt (no CP bar on V toggle).
  (client-bridge/merge-client-bridge!
    {:open-screen open-screen-dispatcher
     :open-reactive-screen open-reactive-screen-handler
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
     :client-overlay-activated-override
     (fn [_owner]
       (when-let [owner (mc-session/current-local-player-owner)]
         (overlay-state/get-client-activated owner)))
     :client-active-overlay-app
     (fn [_owner]
       (when-let [owner (mc-session/current-local-player-owner)]
         (overlay-state/get-active-overlay-app owner)))
     :run-client-effect (fn [effect-key payload]
                          (case effect-key
                            :mcmod/spawn-local-scripted-effect
                            (runtime-bridge/spawn-local-scripted-effect! (:effect-id payload))

                            :mcmod/spawn-scripted-effect-at-player
                            (runtime-bridge/spawn-scripted-effect-at-player!
                              (:effect-id payload) (:owner-uuid payload))

                            :mcmod/remove-local-scripted-effect
                            (runtime-bridge/remove-local-scripted-effect! (:entity-uuid payload))

                            (log/debug "Unhandled client effect key" effect-key)))
	     :get-client-player #(.player (Minecraft/getInstance))
	     :local-player-uuid mc-session/local-player-uuid
	     :set-active-overlay-app (fn [app-kw player-uuid]
	                                (overlay-state/set-active-overlay-app!
	                                  {:client-session-id "" :player-uuid (str player-uuid)}
	                                  app-kw))
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
                           :ring-progbar (cn.li.forge1201.client.render.ModShaders/getSkillProgbarShader)
                           :mono (cn.li.forge1201.client.render.ModShaders/getMonoShader)
                           :alpha-discard (cn.li.forge1201.client.render.ModShaders/getAlphaDiscardShader)
                           nil))
       :get-window-size (fn []
                         (let [^Minecraft mc (Minecraft/getInstance)
                               ^Window win (.getWindow mc)]
                           [(.getGuiScaledWidth win) (.getGuiScaledHeight win)]))
       :register-font! (fn [name spec]
                         (cgui-font/register-font! name spec))
       :get-player-owner #(mc-session/current-local-player-owner)
       :font-text-width (fn [font-desc text font-size]
                          (cgui-font/text-width font-desc text font-size))
       :stop-all-media! (fn [player-uuid]
                          (sound/stop-all-media!))
       :has-recipes? (fn [item-id]
                       (recipe-query/has-recipes? item-id))
       :first-recipe-for (fn [item-id recipe-kind]
                           (recipe-query/first-recipe-for item-id recipe-kind))
       :all-recipes-for (fn [item-id recipe-kind]
                          (recipe-query/all-recipes-for item-id recipe-kind))
       :find-recipes (fn [item-id]
                       (recipe-query/find-recipes item-id))
       :blit-textured-quad! (fn [graphics texture x1 y1 x2 y2 z u0 u1 v0 v1]
                              (.invokeInnerBlit ^GuiGraphicsInvoker graphics
                                texture (int x1) (int x2) (int y1) (int y2) (int z)
                                (float u0) (float u1) (float v0) (float v1)))
       :is-glfw-key-down? (fn [key-code]
                            (try
                              (let [^Minecraft mc (Minecraft/getInstance)
                                    ^Window w (.getWindow mc)
                                    handle (.getWindow w)]
                                (= 1 (org.lwjgl.glfw.GLFW/glfwGetKey handle (int key-code))))
                              (catch Throwable _ false)))
       ;; Terminal 3D perspective + cursor rendering (delegated from ac module)
       :terminal-apply-perspective! cn.li.mc1201.gui.reactive.terminal-render/apply-perspective!
       :terminal-render-cursor!    cn.li.mc1201.gui.reactive.terminal-render/render-cursor!
       :terminal-cursor-hide!      cn.li.mc1201.gui.reactive.terminal-render/hide-cursor!
       :terminal-cursor-show!      cn.li.mc1201.gui.reactive.terminal-render/show-cursor!
       ;; Settings app "keys" category rebinding — Forge-only (Fabric has no
       ;; KeyMapping remapping support; see key-mapping-adapter.clj / fabric's
       ;; keyboard_init.clj comment). Rows still render read-only on Fabric.
       :keybind-rebind-supported?  (constantly true)
       :keybind-get-key-name       key-mapping-adapter/get-key-display-name
       :keybind-set-key!           key-mapping-adapter/set-key-mapping-key!}))

(defn- install-client-owner-hooks!
  []
  (gui-registry/install-client-owner-wrapper! mc-session/with-current-client-owner)
  (runtime-owner/install-client-owner-functions!
    {:client-session-id mc-session/client-session-id
     :with-bound-client-owner mc-session/with-bound-client-owner})
  (gui-network/install-client-owner-functions!
    {:client-session-id mc-session/client-session-id
     :local-player-uuid mc-session/local-player-uuid
     :with-bound-client-owner mc-session/with-bound-client-owner}))

(defn- install-tutorial-activated-bridge!
  []
  (tutorial-hooks/register-tutorial-activated-hook!
    (fn [player-uuid tut-id]
      (try
        (let [uuid (java.util.UUID/fromString player-uuid)
              mc (Minecraft/getInstance)
              player (if (and mc (.hasSingleplayerServer mc))
                       (some-> mc .getSingleplayerServer .getPlayerList (.getPlayer uuid))
                       (when-let [^ClientLevel level (some-> mc .level)]
                         (some (fn [^Player p]
                                 (when (= (str (.getUUID p)) (str uuid)) p))
                               (.players level))))]
          (when player
            (.post MinecraftForge/EVENT_BUS
                   (cn.li.forge1201.event.TutorialActivatedEvent. player (name tut-id)))))
        (catch Throwable e
          (log/stacktrace "install-tutorial-activated-bridge!: hook callback failed" e))))))

(defn- init-client-input-systems!
  []
  ;; Platform SPI installation must happen before AC post-SPI callbacks register
  ;; their keybinding content.
  (try
    (key-scheme-spi/install-provider! (key-scheme-core/get-spi-implementation))
    (catch Exception e
      (log/warn e "Failed to install keyboard input SPI providers")
      (log/stacktrace "Failed to install keyboard input SPI providers" e)))

  (try
    (lifecycle/run-post-spi-client-init!)
    (catch Exception e
      (log/error e "Failed to run post-SPI content keybinding init")
      (log/stacktrace "Failed to run post-SPI content keybinding init" e)))

  (try
    (key-mapping-adapter/register-all-keybindings-from-ac!)
    (catch Exception e
      (log/error e "Failed to register Forge KeyMappings")
      (log/stacktrace "Failed to register Forge KeyMappings" e)))

  (try
    (keyboard-event-handler/install-forge-event-handler!)
    (catch Exception e
      (log/error e "Failed to install Forge keyboard event handler")
      (log/stacktrace "Failed to install Forge keyboard event handler" e))))

(defn register-key-mappings!
  "Register all runtime KeyMapping instances to Forge input system."
  [^RegisterKeyMappingsEvent event]
  (let [all-keys (key-mapping-adapter/get-all-key-mappings)]
    (doseq [^KeyMapping key all-keys]
      (.register event key))
    (log/info "Registered runtime key mappings:" (count all-keys))))

(defn init-client
  "Initialize client-side systems for Forge 1.20.1.
  Called from within the FMLClientSetupEvent handler in mod.clj — do work directly,
  do not attempt to register new event listeners (the event is already firing)."
  []
  (log/info "Initializing Forge 1.20.1 client-side systems")

  (mc-session/init-default-owner-resolver!)
  (install-client-owner-hooks!)
  (install-tutorial-activated-bridge!)
  (init-client-input-systems!)

  ;; Bind client-side rendering implementations first
  (init-render-bindings!)
  (init-content-client-bridge!)
  (gui-screen-impl/init-client!)
  (i18n/install-client-i18n!)

  ;; Register discovered client FX channels (arc-gen, railgun, etc.)
  (ability-client/init-client-fx!)
  ;; Then register renderers
  (register-renderers)
  (register-fluid-render-layers!)

  ;; Runtime client systems
  (runtime-bridge/init!)
  (overlay-renderer/init!)
  (msdf-setup/init!)
  (particle/init!)
  (sound/init!)
  (media-playback-bridge/install-media-playback-bridge!)
  (hand-effect-renderer/init!)
  (level-effect-renderer/init!)
  (request-bridge/init!)

  (energy-item-model-properties/register!)

  ;; Run registered client tick hooks (e.g. tutorial background sync).
  (try
    (.addListener (MinecraftForge/EVENT_BUS)
                  net.minecraftforge.eventbus.api.EventPriority/NORMAL
                  false
                  net.minecraftforge.event.TickEvent$ClientTickEvent
                  (reify java.util.function.Consumer
                    (accept [_ evt]
                      (let [^TickEvent$ClientTickEvent evt evt]
                        (when (= (.phase evt) TickEvent$Phase/END)
                          ;; 铁律六：tick 事件是新调用链入口，必须重建客户端
                          ;; session 上下文（与 runtime-bridge/packet handler 一致），
                          ;; 否则 hook 内 read-model/owner-key 读不到 session-id。
                          (mc-session/with-current-client-session
                            content-actions/run-client-tick-hooks!))))))
    (catch Throwable _
      (log/warn "Failed to register client tick hooks")))

  (log/info "Forge 1.20.1 client-side systems initialized"))

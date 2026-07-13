(ns cn.li.fabric1201.client.init
  "Fabric 1.20.1 client-side initialization and registration"
  (:require [cn.li.mc1201.client.i18n :as i18n]
            [cn.li.mc1201.client.render.pose :as pose-impl]
            [cn.li.mc1201.client.render.buffer :as buffer-impl]
            [cn.li.mc1201.client.screen.host :as screen-host]
            [cn.li.mc1201.client.overlay.state :as overlay-state]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.platform.ui :as platform-ui]
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
            [cn.li.fabric1201.client.keyboard-init :as kb-init]
            [cn.li.mc1201.client.font.msdf-setup :as msdf-setup]
            [cn.li.mc1201.client.session :as mc-session]
            [cn.li.mc1201.gui.reactive.host :as reactive-host]
            [cn.li.mc1201.gui.cgui.font :as cgui-font]
            [cn.li.mc1201.client.effects.sound :as sound]
            [cn.li.mc1201.key-scheme-provider-core :as key-scheme-core]
            [cn.li.mc1201.vanilla-input-control-core :as vanilla-control]
            [cn.li.mcmod.spi.key-scheme-provider :as key-scheme-spi]
            [cn.li.mcmod.spi.vanilla-input-control :as vanilla-spi]
            [cn.li.mcmod.lifecycle :as lifecycle]
            [cn.li.fabric1201.mod :as mod])
  (:import [cn.li.fabric1201.client FabricClientRenderSetup]
           [net.minecraft.client Minecraft]
           [net.minecraft.network.chat Component]
           [cn.li.fabric1201.shim FabricClientHelper]
           [cn.li.fabric1201.shim FabricClientHelper$RendererFactory]
           [cn.li.fabric1201.client.render BlockEntityRendererImpl]
           [com.mojang.blaze3d.platform Window]
           [net.minecraft.world.entity.player Player]
           [cn.li.mc1201.client GuiGraphicsHelper]))

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
    :axis-rotation pose-impl/rotate-axis
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

(defn- open-screen-dispatcher
  "Dispatch open-screen to a registered reactive widget factory, falling back
  to managed screen for keywords without a registered factory."
  [arg payload]
  (when (keyword? arg)
    (if-let [widget (platform-ui/create-widget arg payload)]
      (reactive-host/open-reactive-screen!
        (:runtime widget) (:title widget "Screen") {:on-close (:on-close widget)})
      (screen-host/open-managed-screen! arg payload))))

(defn- open-reactive-screen-handler [& args]
  (apply reactive-host/open-reactive-screen! args))

(defn- init-content-client-bridge!
  []
  (client-bridge/install-client-bridge!
    {:open-screen open-screen-dispatcher
     :open-reactive-screen open-reactive-screen-handler
     :slot-key-down runtime-bridge/on-slot-key-down!
     :slot-key-tick runtime-bridge/on-slot-key-tick!
     :slot-key-up runtime-bridge/on-slot-key-up!
      :slot-key-abort runtime-bridge/on-slot-key-abort!
     :movement-key-down runtime-bridge/on-movement-key-down!
     :movement-key-tick runtime-bridge/on-movement-key-tick!
     :movement-key-up runtime-bridge/on-movement-key-up!
     :client-overlay-activated-override
     (fn [_owner]
       (when-let [owner (mc-session/current-local-player-owner)]
         (overlay-state/get-client-activated owner)))
     :client-active-overlay-app
     (fn [_owner]
       (when-let [owner (mc-session/current-local-player-owner)]
         (overlay-state/get-active-overlay-app owner)))
	     :get-client-player #(.player (Minecraft/getInstance))
	     :local-player-uuid mc-session/local-player-uuid
	     :set-active-overlay-app (fn [app-kw player-uuid]
	                                (overlay-state/set-active-overlay-app!
	                                  {:client-session-id "" :player-uuid (str player-uuid)}
	                                  app-kw))
	     :screen-active? #(some? (.screen (Minecraft/getInstance)))
	     :close-screen! #(.setScreen (Minecraft/getInstance) nil)
	     :send-system-message! (fn [^Player player translatable-key & args]
	                              (.sendSystemMessage player
	                                (Component/translatable translatable-key (into-array Object args))))
     ;; === Rendering bridge (parity with Forge init.clj) ===
     :resolve-shader (fn [_shader-name]
                       nil)
     :blit-textured-quad! (fn [graphics texture x1 y1 x2 y2 z u0 u1 v0 v1]
                            (GuiGraphicsHelper/blitTexturedQuad
                              graphics texture (float x1) (float y1) (float x2) (float y2) (float z)
                              (float u0) (float u1) (float v0) (float v1)))
     :get-window-size (fn []
                        (let [^Minecraft mc (Minecraft/getInstance)
                              ^Window win (.getWindow mc)]
                          [(.getGuiScaledWidth win) (.getGuiScaledHeight win)]))
     :register-font! (fn [name spec]
                       (cgui-font/register-font! name spec))
     :get-player-owner #(mc-session/current-local-player-owner)
     :font-text-width (fn [font-desc text font-size]
                        (cgui-font/text-width font-desc text font-size))
     :font-width (fn [^String text]
                   (let [^Minecraft mc (Minecraft/getInstance)]
                     (.width (.-font mc) text)))
     :stop-all-media! (fn [player-uuid]
                        (sound/stop-all-media!))
     :is-glfw-key-down? (fn [key-code]
                          (try
                            (let [^Minecraft mc (Minecraft/getInstance)
                                  ^Window win (.getWindow mc)
                                  handle (.getWindow win)]
                              (= 1 (org.lwjgl.glfw.GLFW/glfwGetKey handle (int key-code))))
                            (catch Throwable _ false)))}))

(defn init-client
  "Initialize client-side systems for Fabric 1.20.1."
  []
  (log/info "Initializing Fabric 1.20.1 client-side systems")
  
  ;; ===== Platform SPI Installation (before AC bootstrap) =====
  ;; Install Fabric-specific SPI implementations that AC will use
  (try
    (key-scheme-spi/install-provider! (key-scheme-core/get-spi-implementation))
    (vanilla-spi/install-suppressor! (vanilla-control/get-spi-implementation))
    (catch Exception e
      (log/warn e "Failed to install keyboard input SPI providers")))
  
  ;; ===== Content Keybinding Initialization (post-SPI) =====
  ;; Run content-registered post-SPI init callbacks (e.g. AC keybindings)
  (try
    (lifecycle/run-post-spi-client-init!)
    (catch Exception e
      (log/error e "Failed to run post-SPI content keybinding init")))
  
  ;; ===== Fabric Keyboard Handler Installation =====
  ;; Install GLFW polling for keyboard inputs (Fabric has no native keyboard events)
  (try
    (kb-init/install-keyboard-handler!)
    (catch Exception e
      (log/error e "Failed to install Fabric keyboard handler")))
  
  ;; ===== Standard Client Initialization =====
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
  (msdf-setup/init!)
  (log/info "Fabric client initialization complete"))

(ns cn.li.fabric1201.client.init
  "Fabric 1.20.1 client-side initialization and registration"
  (:require [cn.li.mc1201.client.i18n :as i18n]
            [cn.li.mc1201.client.render.pose :as pose-impl]
            [cn.li.mc1201.client.render.buffer :as buffer-impl]
            [cn.li.mcbase.client.overlay.state :as overlay-state]
            [cn.li.mc1201.client.energy-item-model-properties :as energy-item-model-properties]
            [cn.li.mc1201.integration.recipe-query :as recipe-query]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.client.ui.registry :as widget-registry]
            [cn.li.mcmod.util.render :as render]
            [cn.li.mcmod.client.render.pose :as pose]
            [cn.li.mcmod.client.render.buffer :as buffer]
            [cn.li.mcmod.client.render.init :as render-init]
            [cn.li.mcmod.protocol.metadata :as registry-metadata]
            [cn.li.fabric1201.adapter.gui-registry :as gui-registry]
            [cn.li.fabric1201.client.overlay-renderer :as overlay-renderer]
            [cn.li.fabric1201.client.hand-effect-renderer :as hand-effect-renderer]
            [cn.li.fabric1201.client.level-effect-renderer :as level-effect-renderer]
            [cn.li.fabric1201.client.keyboard-init :as kb-init]
            [cn.li.fabric1201.client.obj-model-registration :as obj-models]
            [cn.li.mc1201.client.font.msdf-setup :as msdf-setup]
            [cn.li.mcbase.client.session :as mc-session]
            [cn.li.mc1201.gui.reactive.host :as reactive-host]
            [cn.li.mc1201.gui.reactive.terminal-render :as terminal-render]
            [cn.li.mc1201.gui.cgui.font :as cgui-font]
            [cn.li.mc1201.client.effects.sound :as sound]
            [cn.li.mcbase.client.audio.media-playback :as media-playback-bridge]
            [cn.li.mc1201.key-scheme-provider-core :as key-scheme-core]
            [cn.li.mc1201.vanilla-input-control-core :as vanilla-control]
            [cn.li.mcmod.spi.key-scheme-provider :as key-scheme-spi]
            [cn.li.mcmod.spi.vanilla-input-control :as vanilla-spi]
            [cn.li.mcmod.lifecycle :as lifecycle]
            [cn.li.fabric1201.mod :as mod])
  (:import [cn.li.fabric1201.client FabricClientRenderSetup]
           [cn.li.fabric1201.shim FabricClientHelper]
           [net.minecraft.client Minecraft]
           [net.minecraft.network.chat Component]
           [cn.li.mc1201.client ClientHelper]
           [cn.li.mc1201.client ClientHelper$RendererFactory]
           [cn.li.mc1201.client.render ScriptedBlockEntityBer]
           [cn.li.mc1201.client.render ModRenderTypes]
           [com.mojang.blaze3d.platform Window]
           [net.minecraft.world.entity.player Player]
           [cn.li.mc1201.client GuiGraphicsHelper]
           [cn.li.mc1201.client.effect ScriptedEffectSpawner]))

(defn- bind-texture-fabric!
  "Bind a texture for rendering."
  [texture]
  (ClientHelper/bindTextureForSetup texture))

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
    :triangle-vertex-order (fn [] [0 1 2 2])}
   "fabric-client")

  (log/info "Fabric client-side rendering bindings complete"))

(defn register-scripted-block-entity-renderers!
  "Attach a single universal BlockEntity renderer to all scripted tile types."
  []
  (doseq [tile-id (registry-metadata/get-all-tile-ids)]
    (when-let [be-type (mod/get-registered-block-entity-type tile-id)]
      (ClientHelper/registerBlockEntityRenderer
        be-type
        (reify ClientHelper$RendererFactory
          (create [_]
            (ScriptedBlockEntityBer.))))
      (log/info (str "Fabric BER registered for tile-id " tile-id)))))

(defn- open-screen-dispatcher
  "Dispatch open-screen to a registered reactive widget factory."
  [arg payload]
  (when (keyword? arg)
    (let [widget (widget-registry/create-widget arg payload)]
      (reactive-host/open-reactive-screen!
        (:runtime widget) (:title widget "Screen") {:on-close (:on-close widget)}))))

(defn- open-reactive-screen-handler [& args]
  (apply reactive-host/open-reactive-screen! args))

(defn- init-content-client-bridge!
  []
  ;; MERGE, not install: install-client-bridge! REPLACES the whole map and
  ;; wipes adapters content modules registered earlier during modloading
  ;; (ac's :reactive-overlay-build/update — see forge init note).
  (client-bridge/merge-client-bridge!
    {:open-screen open-screen-dispatcher
     :open-reactive-screen open-reactive-screen-handler
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
     :singleplayer? #(.hasSingleplayerServer (Minecraft/getInstance))
     :settings-key-name key-scheme-core/key-display-name
     :close-screen! #(.setScreen (Minecraft/getInstance) nil)
     :send-system-message! (fn [^Player player translatable-key & args]
                              (.sendSystemMessage player
                                (Component/translatable translatable-key (into-array Object args))))
     :resolve-shader (fn [shader-name]
                       (case shader-name
                         :ring-progbar (ModRenderTypes/getSkillProgbarShader)
                         :mono (ModRenderTypes/getMonoShader)
                         :cpbar-overload (ModRenderTypes/getCpbarOverloadShader)
                         :alpha-discard (ModRenderTypes/getAlphaDiscardShader)
                         nil))
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
     :run-client-effect (fn [effect-key payload]
                          (case effect-key
                            :mcmod/spawn-local-scripted-effect
                            (ScriptedEffectSpawner/spawnLocalWithUuid (:effect-id payload))

                            :mcmod/spawn-local-scripted-effect-at
                            (ScriptedEffectSpawner/spawnLocalAt
                              (:effect-id payload)
                              (:x payload) (:y payload) (:z payload))

                            :mcmod/spawn-scripted-effect-at-player
                            (ScriptedEffectSpawner/spawnAtPlayerWithUuid
                              (:effect-id payload) (:owner-uuid payload))

                            :mcmod/remove-local-scripted-effect
                            (ScriptedEffectSpawner/removeLocalByUuid (:entity-uuid payload))

                            :mcmod/start-loop-sound
                            (sound/start-loop-sound! (:key payload) (:sound-id payload)
                              (:volume payload) (:pitch payload)
                              (:x payload) (:y payload) (:z payload))

                            :mcmod/start-loop-sound-at-player
                            (sound/start-loop-sound-at-player!
                              (:key payload) (:sound-id payload)
                              (:volume payload) (:pitch payload)
                              (:owner-uuid payload))

                            :mcmod/update-loop-sound-position
                            (sound/update-loop-sound-position! (:key payload)
                              (:x payload) (:y payload) (:z payload))

                            :mcmod/stop-loop-sound
                            (sound/stop-loop-sound! (:key payload))

                            (log/debug "Unhandled client effect key" effect-key)))
     :is-glfw-key-down? (fn [key-code]
                          (try
                            (let [^Minecraft mc (Minecraft/getInstance)
                                  ^Window win (.getWindow mc)
                                  handle (.getWindow win)]
                              (= 1 (org.lwjgl.glfw.GLFW/glfwGetKey handle (int key-code))))
                            (catch Throwable _ false)))
     :has-recipes? (fn [item-id]
                     (recipe-query/has-recipes? item-id))
     :first-recipe-for (fn [item-id recipe-kind]
                         (recipe-query/first-recipe-for item-id recipe-kind))
     :all-recipes-for (fn [item-id recipe-kind]
                        (recipe-query/all-recipes-for item-id recipe-kind))
     :find-recipes (fn [item-id]
                     (recipe-query/find-recipes item-id))
     :terminal-apply-perspective! cn.li.mc1201.gui.reactive.terminal-render/apply-perspective!
     :terminal-render-cursor!    cn.li.mc1201.gui.reactive.terminal-render/render-cursor!
     :terminal-cursor-hide!      cn.li.mc1201.gui.reactive.terminal-render/hide-cursor!
     :terminal-cursor-show!      cn.li.mc1201.gui.reactive.terminal-render/show-cursor!}))

(defn- install-client-owner-hooks!
  []
  (gui-registry/install-client-owner-wrapper! mc-session/with-current-client-owner))

(defn- register-fluid-client!
  "Register translucent layers + SimpleFluidRenderHandler for all DSL fluids."
  []
  (doseq [fluid-id (registry-metadata/get-all-fluid-ids)]
    (let [fluid-spec (registry-metadata/get-fluid-spec fluid-id)
          rendering (:rendering fluid-spec)
          translucent? (true? (:is-translucent rendering))
          source (mod/get-registered-fluid-source fluid-id)
          flowing (mod/get-registered-fluid-flowing fluid-id)]
      (when (and source flowing)
        (when translucent?
          (FabricClientHelper/setFluidRenderLayerTranslucent source flowing))
        (when (and (:still-texture rendering) (:flowing-texture rendering))
          (FabricClientHelper/registerSimpleFluidRenderHandler
            source
            flowing
            (str (:still-texture rendering))
            (str (:flowing-texture rendering))
            (when-let [overlay (:overlay-texture rendering)]
              (str overlay))
            (unchecked-int (or (:tint-color rendering) -1))))))))

(defn init-client
  "Initialize client-side systems for Fabric 1.20.1."
  []
  (log/info "Initializing Fabric 1.20.1 client-side systems")

  (mc-session/init-default-owner-resolver!)
  (install-client-owner-hooks!)

  (try
    (key-scheme-spi/install-provider! (key-scheme-core/get-spi-implementation))
    (vanilla-spi/install-suppressor! (vanilla-control/get-spi-implementation))
    (catch Exception e
      (log/warn e "Failed to install keyboard input SPI providers")))

  (try
    (lifecycle/run-post-spi-client-init!)
    (catch Exception e
      (log/error e "Failed to run post-SPI content keybinding init")))

  (try
    (kb-init/install-keyboard-handler!)
    (catch Exception e
      (log/error e "Failed to install Fabric keyboard handler")))

  (init-render-bindings!)
  (init-content-client-bridge!)
  (i18n/install-client-i18n!)
  (register-renderers)
  (FabricClientRenderSetup/registerEntityRenderers)
  (register-scripted-block-entity-renderers!)
  (register-fluid-client!)
  (energy-item-model-properties/register!)
  (obj-models/register!)
  (overlay-renderer/init!)
  (hand-effect-renderer/init!)
  (level-effect-renderer/init!)
  (msdf-setup/init!)
  (media-playback-bridge/install-media-playback-bridge!)
  (log/info "Fabric client initialization complete"))

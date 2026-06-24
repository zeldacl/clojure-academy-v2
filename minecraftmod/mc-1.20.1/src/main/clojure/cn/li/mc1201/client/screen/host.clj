(ns cn.li.mc1201.client.screen.host
  "CLIENT-ONLY generic screen host. AC provides draw ops and interaction handlers."
  (:require [cn.li.mc1201.client.session :as client-session]
            [cn.li.mcmod.config :as mcmod-config]
            [cn.li.mcmod.hooks.core :as client-ui]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mc1201.client.render.shader-utils :as shader-utils]
            [clojure.string :as str])
  (:import [net.minecraft.client.gui.screens Screen]
           [net.minecraft.client.gui GuiGraphics Font]
           [net.minecraft.network.chat Component]
           [net.minecraft.client Minecraft]
           [net.minecraft.client.renderer ShaderInstance]
           [net.minecraft.resources ResourceLocation]
           [com.mojang.blaze3d.systems RenderSystem]
           [com.mojang.blaze3d.vertex DefaultVertexFormat VertexFormat]
           [org.lwjgl.opengl GL11]))

;; ============================================================================
;; Texture preloads (developer skill tree)
;; ============================================================================

(let [mod-id (or mcmod-config/*mod-id* "my_mod")]
  ;; Paths WITHOUT "textures/" and ".png" — Minecraft's SimpleTexture adds them automatically.
  (def ^:private skill-tree-textures
    {:skill-back           (ResourceLocation. mod-id "guis/developer/skill_back")
     :skill-outline        (ResourceLocation. mod-id "guis/developer/skill_outline")
     :skill-mask           (ResourceLocation. mod-id "guis/developer/skill_radial_mask")
     :skill-view-outline   (ResourceLocation. mod-id "guis/developer/skill_view_outline")
     :skill-view-outline-glow (ResourceLocation. mod-id "guis/developer/skill_view_outline_glow")
     :tex-line             (ResourceLocation. mod-id "guis/developer/line")
     :tex-button           (ResourceLocation. mod-id "guis/developer/button")
     :bg-area              (ResourceLocation. mod-id "guis/effect/effect_developer_background")}))

(defn- get-skill-tree-texture [key]
  (get skill-tree-textures key))

;; ============================================================================
;; Drawing helpers
;; ============================================================================

(defn- draw-string! [^GuiGraphics graphics ^String text x y color]
  (let [^Minecraft mc (Minecraft/getInstance)
        ^Font font (.-font mc)]
    (.drawString graphics font text (int x) (int y) (int color))))

(defn- draw-string-with-opts! [^GuiGraphics graphics op poseStack]
  (let [^String text (str (:text op))
        x (int (or (:x op) 0))
        y (int (or (:y op) 0))
        color (int (or (:color op) 0xFFFFFFFF))
        font-size (or (:font-size op) 9)
        align (or (:align op) :left)
        ^Minecraft mc (Minecraft/getInstance)
        ^Font font (.-font mc)
        text-width (.width font text)
        scale (/ (double font-size) 9.0)]
    ;; Apply scale for font-size
    (when (not= 1.0 scale)
      (.pushPose poseStack)
      (.translate poseStack (double x) (double y) 0.0)
      (.scale poseStack (float scale) (float scale) 1.0)
      (.translate poseStack (double (- x)) (double (- y)) 0.0))
    ;; Apply alignment offset
    (let [draw-x (case align
                   :center (- x (/ text-width 2.0))
                   :right  (- x text-width)
                   x)]
      (.drawString graphics font text (int draw-x) (int y) (int color)))
    (when (not= 1.0 scale)
      (.popPose poseStack))))

(defn- with-client-session
  [session-id f]
  (binding [client-ui/*client-session-id* session-id]
    (f)))

(defn- augment-payload-owner
  [payload]
  (let [payload (or payload {})
        owner (client-session/require-local-player-owner)
        payload-player-uuid (:player-uuid payload)
        payload-session-id (:client-session-id payload)]
    (when (and payload-player-uuid
               (not= payload-player-uuid (:player-uuid owner)))
      (throw (ex-info "Managed screen payload player UUID must match local owner"
                      {:payload payload
                       :owner owner})))
    (when (and payload-session-id
               (not= payload-session-id (:client-session-id owner)))
      (throw (ex-info "Managed screen payload client session must match local owner"
                      {:payload payload
                       :owner owner})))
    (merge payload owner {:player-uuid (:player-uuid owner)})))

(defn- normalize-texture-path [path]
  "Convert a content-owned texture path to namespaced ResourceLocation format.
  Strips 'textures/' prefix and '.png' suffix because Minecraft's SimpleTexture
  (used by TextureManager.getTexture / GuiGraphics.blit) adds them automatically."
  (when (and path (not (str/blank? path)))
    (if (str/includes? path ":")
      path
      (let [mod-id (or mcmod-config/*mod-id* "my_mod")
            stripped (cond-> path
                       (str/starts-with? path "textures/") (subs (count "textures/"))
                       (str/ends-with? path ".png") (subs 0 (- (count path) 4)))]
        (str mod-id ":" stripped)))))

(defn- render-op! [^GuiGraphics graphics op poseStack]
  (case (:kind op)
    ;; --- Matrix operations ---
    :push-pose (.pushPose poseStack)
    :pop-pose  (.popPose poseStack)
    :translate (let [x (double (or (:x op) 0.0))
                     y (double (or (:y op) 0.0))
                     z (double (or (:z op) 0.0))]
                 (.translate poseStack x y z))
    :scale     (let [cx (double (or (:cx op) 0.0))
                     cy (double (or (:cy op) 0.0))
                     s (double (or (:s op) 1.0))]
                 (.translate poseStack cx cy 0.0)
                 (.scale poseStack (float s) (float s) 1.0)
                 (.translate poseStack (- cx) (- cy) 0.0))
    ;; --- Depth state ---
    :enable-depth  (RenderSystem/enableDepthTest)
    :disable-depth (RenderSystem/disableDepthTest)
    :depth-mask    (RenderSystem/depthMask (boolean (:write? op true)))
    :depth-func    (let [func (:func op)]
                     (case func
                       :equal    (GL11/glDepthFunc GL11/GL_EQUAL)
                       :lequal   (GL11/glDepthFunc GL11/GL_LEQUAL)
                       :notequal (GL11/glDepthFunc GL11/GL_NOTEQUAL)
                       nil))
    ;; --- Blend / alpha ---
    :enable-blend  (RenderSystem/enableBlend)
    :alpha-color   (RenderSystem/setShaderColor
                     (float (or (:r op) 1.0))
                     (float (or (:g op) 1.0))
                     (float (or (:b op) 1.0))
                     (float (or (:a op) 1.0)))
    :color-mask    (let [m (boolean (:write? op true))]
                     (GL11/glColorMask m m m m))
    ;; --- Text ---
    :text (draw-string-with-opts! graphics op poseStack)
    ;; --- Fills ---
    :fill (.fill graphics (:x op) (:y op) (+ (:x op) (:w op)) (+ (:y op) (:h op)) (:color op))
    ;; --- Textures ---
    :icon-or-fill (if-let [loc (some-> (:texture op) normalize-texture-path ResourceLocation/tryParse)]
                    (.blit graphics loc (:x op) (:y op) 0 0 (:w op) (:h op) (:w op) (:h op))
                    (.fill graphics (:x op) (:y op) (+ (:x op) (:w op)) (+ (:y op) (:h op)) (:fallback-color op)))
    :textured-quad (let [tex-key (:texture op)
                         loc (if (keyword? tex-key) (get-skill-tree-texture tex-key)
                                 (some-> tex-key normalize-texture-path ResourceLocation/tryParse))]
                     (when loc
                       (.blit graphics loc (:x op) (:y op) 0 0 (:w op) (:h op) (:w op) (:h op))))
    :raw-rect-uv   (let [tex-key (:texture op)
                         loc (if (keyword? tex-key) (get-skill-tree-texture tex-key)
                                 (some-> tex-key normalize-texture-path ResourceLocation/tryParse))]
                     (when loc
                       (.innerBlit graphics loc
                                   (int (:x op)) (int (+ (:x op) (:w op)))
                                   (int (:y op)) (int (+ (:y op) (:h op)))
                                   0
                                   (float (or (:min-u op) (:u op) 0.0))
                                   (float (or (:max-u op) (+ (or (:u op) 0.0) (or (:tex-w op) 1.0))))
                                   (float (or (:min-v op) (:v op) 0.0))
                                   (float (or (:max-v op) (+ (or (:v op) 0.0) (or (:tex-h op) 1.0)))))))
    ;; --- Connection line (rotated textured quad via staggered fill segments) ---
    :rotated-quad (let [x0 (double (:x0 op)) y0 (double (:y0 op))
                        x1 (double (:x1 op)) y1 (double (:y1 op))
                        width (int (Math/ceil (double (:line-width op 5.5))))
                        steps (max 1 (int (Math/ceil (Math/max (Math/abs (- x1 x0)) (Math/abs (- y1 y0))))))]
                    (doseq [i (range steps)]
                      (let [t (/ i (double steps))
                            px (int (+ x0 (* (- x1 x0) t)))
                            py (int (+ y0 (* (- y1 y0) t)))]
                        (.fill graphics px py (+ px width) (+ py width) (:color op 0x80999999)))))
    ;; --- Shader-based progress ring ---
    :shader-progress-ring
    (let [^ShaderInstance si (shader-utils/resolve-shader (:shader-id op))
          tex-0-key (:texture-0 op)
          tex-1-key (:texture-1 op)
          loc-0 (if (keyword? tex-0-key) (get-skill-tree-texture tex-0-key)
                    (some-> tex-0-key normalize-texture-path ResourceLocation/tryParse))
          loc-1 (when tex-1-key
                  (if (keyword? tex-1-key) (get-skill-tree-texture tex-1-key)
                      (some-> tex-1-key normalize-texture-path ResourceLocation/tryParse)))
          progress (float (or (:progress op) 0.0))]
      (if si
        (when loc-0
          (.setSampler si "TexSampler0" loc-0)
          (when loc-1 (.setSampler si "TexSampler1" loc-1))
          (RenderSystem/setShader (reify java.util.function.Supplier (get [_] si)))
          (when-let [u (.safeGetUniform si "Progress")]
            (.set u progress))
          (.blit graphics loc-0 (:x op) (:y op) 0 0 (:w op) (:h op) (:w op) (:h op))
          (RenderSystem/setShader (reify java.util.function.Supplier (get [_] nil))))
        ;; Fallback: render icon without shader (for environments where shaders fail to load)
        (if loc-0
          (.blit graphics loc-0 (:x op) (:y op) 0 0 (:w op) (:h op) (:w op) (:h op))
          (.fill graphics (:x op) (:y op) (+ (:x op) (:w op)) (+ (:y op) (:h op)) 0xFF2A2A2A))))
    ;; --- Progress ring (fallback, non-shader) ---
    :progress-ring
    (let [segments (max 1 (int (or (:segments op) 24)))
          filled (int (max 0 (min segments (or (:filled-segments op) 0))))
          x (double (:x op))
          y (double (:y op))
          size (double (or (:size op) 20))
          cx (+ x (/ size 2.0))
          cy (+ y (/ size 2.0))
          radius (- (/ size 2.0) 1.0)
          base-color 0x99484848
          fill-color 0xFF8FD3FF]
      (doseq [idx (range segments)]
        (let [theta (* (/ (* 2.0 Math/PI) segments) idx)
              px (+ cx (* radius (Math/cos theta)))
              py (+ cy (* radius (Math/sin theta)))
              color (if (< idx filled) fill-color base-color)]
          (.fill graphics (int (Math/floor px)) (int (Math/floor py))
                 (int (Math/ceil (+ px 1.0))) (int (Math/ceil (+ py 1.0)))
                 (unchecked-int color)))))
    nil))

(defn- create-host-screen
  ([title draw-ops-fn click-fn hover-fn close-fn]
   (create-host-screen title draw-ops-fn click-fn hover-fn close-fn nil))
  ([title draw-ops-fn click-fn hover-fn close-fn char-typed-fn]
   (proxy [Screen] [(Component/literal title)]
    (render [^GuiGraphics graphics mouse-x mouse-y _partial-tick]
      (try
        (let [^Screen screen this
              poseStack (.pose graphics)]
          (.renderBackground screen graphics)
          (when hover-fn
            (hover-fn mouse-x mouse-y))
          (doseq [op (draw-ops-fn mouse-x mouse-y)]
            (render-op! graphics op poseStack)))
        (catch Exception e
          (log/error (str "Error rendering hosted screen " title) e))))

    (keyPressed [^long key ^long _scancode ^long _modifiers]
      (cond
        (= key 256)
        (let [^Minecraft mc (Minecraft/getInstance)]
          (.setScreen mc nil)
          true)
        (and char-typed-fn (= key 259))
        (do (char-typed-fn \backspace) true)
        (and char-typed-fn (= key 257))
        (do (char-typed-fn \newline) true)
        :else false))

    (charTyped [ch _modifiers]
      (if char-typed-fn
        (do (char-typed-fn ch) true)
        false))

    (mouseClicked [mouse-x mouse-y _button]
      (try
        (boolean (click-fn mouse-x mouse-y))
        (catch Exception e
          (log/error (str "Error handling hosted screen click " title) e)
          false)))

    (removed []
      (when close-fn
        (close-fn))))))

(defn open-managed-screen!
  "Open a content-owned hosted screen by opaque screen key and payload."
  [screen-key payload]
  (let [payload* (augment-payload-owner payload)
        captured-session-id (:client-session-id payload*)
        result (with-client-session captured-session-id
                 #(client-ui/client-open-managed-screen! screen-key payload*))]
    (when (= (:command result) :open-screen)
      (let [^Minecraft mc (Minecraft/getInstance)
            title (or (:title result) "Managed Screen")
            char-typed-fn (when (:char-typed? result)
                            (fn [ch]
                              (with-client-session captured-session-id
                                #(client-ui/client-handle-managed-screen-char-typed! screen-key ch))))]
        (.setScreen mc
                    (create-host-screen
                      title
                      (fn [mouse-x mouse-y]
                        (with-client-session captured-session-id
                          #(client-ui/client-build-managed-screen-draw-ops screen-key mouse-x mouse-y)))
                      (fn [mouse-x mouse-y]
                        (with-client-session captured-session-id
                          #(client-ui/client-handle-managed-screen-click! screen-key mouse-x mouse-y)))
                      (fn [mouse-x mouse-y]
                        (with-client-session captured-session-id
                          #(client-ui/client-handle-managed-screen-hover! screen-key mouse-x mouse-y)))
                      (fn []
                        (with-client-session captured-session-id
                          #(client-ui/client-close-managed-screen! screen-key)))
                      char-typed-fn))))))

(defn init! []
  (log/info "Client screen host initialized"))

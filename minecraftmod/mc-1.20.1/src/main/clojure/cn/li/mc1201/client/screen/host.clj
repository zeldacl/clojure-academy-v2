(ns cn.li.mc1201.client.screen.host
  "CLIENT-ONLY generic screen host. AC provides draw ops and interaction handlers."
  (:require [cn.li.mc1201.client.session :as client-session]
            [cn.li.mcmod.config :as mcmod-config]
            [cn.li.mcmod.hooks.core :as client-ui]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.client.platform-bridge :as platform-bridge]
            [clojure.string :as str])
  (:import [net.minecraft.client.gui.screens Screen]
           [net.minecraft.client.gui GuiGraphics Font]
           [net.minecraft.network.chat Component]
           [net.minecraft.client Minecraft]
           [net.minecraft.client.renderer ShaderInstance]
           [net.minecraft.resources ResourceLocation]
           [com.mojang.blaze3d.systems RenderSystem]
           [com.mojang.blaze3d.vertex DefaultVertexFormat VertexFormat VertexFormat$Mode PoseStack
            Tesselator BufferBuilder BufferUploader PoseStack$Pose]
           [com.mojang.blaze3d.platform Window]
           [org.joml Matrix4f]
           [org.lwjgl.opengl GL11]
           [cn.li.mc1201.client GuiGraphicsHelper]))

;; ============================================================================
;; Texture preloads (developer skill tree)
;; ============================================================================

(let [mod-id (or mcmod-config/*mod-id* "my_mod")]
  (def ^:private skill-tree-textures
    {:skill-back           (ResourceLocation. mod-id "textures/guis/developer/skill_back.png")
     :skill-outline        (ResourceLocation. mod-id "textures/guis/developer/skill_outline.png")
     :skill-mask           (ResourceLocation. mod-id "textures/guis/developer/skill_radial_mask.png")
     :skill-view-outline   (ResourceLocation. mod-id "textures/guis/developer/skill_view_outline.png")
     :skill-view-outline-glow (ResourceLocation. mod-id "textures/guis/developer/skill_view_outline_glow.png")
     :tex-line             (ResourceLocation. mod-id "textures/guis/developer/line.png")
     :tex-button           (ResourceLocation. mod-id "textures/guis/developer/button.png")
     :bg-area              (ResourceLocation. mod-id "textures/guis/effect/effect_developer_background.png")}))

(defn- get-skill-tree-texture [key]
  (get skill-tree-textures key))

;; ============================================================================
;; Drawing helpers
;; ============================================================================

(defn- draw-string! [^GuiGraphics graphics ^String text x y color]
  (let [^Minecraft mc (Minecraft/getInstance)
        ^Font font (.-font mc)]
    (.drawString graphics font text (int x) (int y) (int color))))

(defn- draw-string-with-opts! [^GuiGraphics graphics op ^PoseStack poseStack]
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

(defn- render-custom-shader-quad!
  "Render a textured quad using a custom ShaderInstance — bypasses GuiGraphics/blit
  which forcibly sets GameRenderer/positionTexShader, overriding any custom shader.

  The caller must set the desired shader via RenderSystem/setShader BEFORE calling this,
  and restore it afterwards. Samplers must be set via .setSampler on the shader instance
  BEFORE calling setShader (MC 1.20.1 requires setSampler before setShader).

  Uses BufferBuilder + BufferUploader (the MC 1.20.1 recommended path for custom shaders)."
  [^GuiGraphics graphics ^PoseStack poseStack ^ShaderInstance si ^ResourceLocation loc-0 ^ResourceLocation loc-1 x y w h]
  (let [^PoseStack$Pose entry (.last poseStack)
        ^Matrix4f pose-matrix (.pose entry)
        ^Tesselator tess (Tesselator/getInstance)
        ^BufferBuilder bb (.getBuilder tess)
        x1 (float x)  y1 (float y)
        x2 (float (+ x w)) y2 (float (+ y h))
        z (float 0)
        u0 (float 0.0) v0 (float 0.0) u1 (float 1.0) v1 (float 1.0)]
    (RenderSystem/setShaderTexture (int 0) loc-0)
    (when loc-1
      (RenderSystem/setShaderTexture (int 1) loc-1))
    (.begin bb VertexFormat$Mode/QUADS DefaultVertexFormat/POSITION_TEX)
    (.vertex bb pose-matrix x1 y2 z) (.uv bb u0 v1) (.endVertex bb)
    (.vertex bb pose-matrix x2 y2 z) (.uv bb u1 v1) (.endVertex bb)
    (.vertex bb pose-matrix x2 y1 z) (.uv bb u1 v0) (.endVertex bb)
    (.vertex bb pose-matrix x1 y1 z) (.uv bb u0 v0) (.endVertex bb)
    (BufferUploader/drawWithShader (.end bb))))

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

(defn- path->resource-location
  "Build a ResourceLocation from a content texture path string.
  Uses the 2-arg constructor directly (bypassing tryParse which may silently fail)."
  [path]
  (when (and path (not (str/blank? path)))
    (let [mod-id (or mcmod-config/*mod-id* "my_mod")]
      (if (str/includes? path ":")
        (let [[ns p] (str/split path #":" 2)]
          (ResourceLocation. ns p))
        (if (str/starts-with? path "textures/")
          (ResourceLocation. mod-id path)
          (ResourceLocation. mod-id (str "textures/" path)))))))

(defn- render-op! [^GuiGraphics graphics op ^PoseStack poseStack]
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
    :icon-or-fill (let [raw-tex (:texture op)
                        loc (path->resource-location raw-tex)]
                    (when (nil? loc)
                      (log/error "[skill-tree-debug] icon-or-fill FAILED to resolve texture:" (pr-str raw-tex) "normalized:" (pr-str (path->resource-location raw-tex))))
                    (if loc
                      (.blit graphics loc (:x op) (:y op) 0 0 (:w op) (:h op) (:w op) (:h op))
                      (.fill graphics (:x op) (:y op) (+ (:x op) (:w op)) (+ (:y op) (:h op)) (:fallback-color op))))
    :textured-quad (let [tex-key (:texture op)
                         loc (if (keyword? tex-key) (get-skill-tree-texture tex-key)
                                 (path->resource-location tex-key))]
                     (when (and (not (keyword? tex-key)) (nil? (path->resource-location tex-key)))
                       (log/error "[skill-tree-debug] textured-quad FAILED to resolve:" (pr-str tex-key)))
                     (when loc
                       (.blit graphics loc (:x op) (:y op) 0 0 (:w op) (:h op) (:w op) (:h op))))
    :raw-rect-uv   (let [tex-key (:texture op)
                         ^ResourceLocation loc (if (keyword? tex-key) (get-skill-tree-texture tex-key)
                                               (path->resource-location tex-key))]
                     (when loc
                       (GuiGraphicsHelper/innerBlit10 graphics loc
                                                      (int (:x op)) (int (+ (:x op) (:w op)))
                                                      (int (:y op)) (int (+ (:y op) (:h op)))
                                                      (int 0)
                                                      (float (or (:min-u op) (:u op) 0.0))
                                                      (float (or (:max-u op) (+ (or (:u op) 0.0) (or (:tex-w op) 1.0))))
                                                      (float (or (:min-v op) (:v op) 0.0))
                                                      (float (or (:max-v op) (+ (or (:v op) 0.0) (or (:tex-h op) 1.0)))))))
    ;; --- Connection line (textured quad, matching upstream drawLine) ---
    ;; Upstream uses glVertex2d with double precision and texture (0,0)→(1,1) stretched.
    ;; Previous rotated-quad approach used blit() which truncates to int and
    ;; maps texture 1:1, causing visible stepping/segmentation at rotation angles.
    ;; BufferBuilder with float vertices and normalized UVs fixes this.
    :line-quad (let [^ResourceLocation tex (get-skill-tree-texture :tex-line)
                     x0 (double (:x0 op)) y0 (double (:y0 op))
                     x1 (double (:x1 op)) y1 (double (:y1 op))
                     line-w (double (:line-width op 5.5))
                     color (int (or (:color op) 0xFFFFFFFF))
                     alpha (double (/ (bit-and (bit-shift-right color 24) 0xFF) 255.0))]
                 (when (and tex (pos? alpha) (pos? color))
                   (let [dx (- x1 x0) dy (- y1 y0)
                         norm (Math/sqrt (+ (* dx dx) (* dy dy)))]
                     (when (pos? norm)
                       (let [half-w (/ line-w 2.0)
                             ;; Perpendicular normal (matching upstream drawLine)
                             nx (* (/ (- dy) norm) half-w)
                             ny (* (/ dx norm) half-w)
                             ^PoseStack$Pose entry (.last poseStack)
                             ^Matrix4f pose-matrix (.pose entry)
                             ^Tesselator tess (Tesselator/getInstance)
                             ^BufferBuilder bb (.getBuilder tess)]
                         (RenderSystem/setShaderColor 1.0 1.0 1.0 (float alpha))
                         (RenderSystem/enableBlend)
                         (RenderSystem/defaultBlendFunc)
                         (RenderSystem/setShaderTexture (int 0) tex)
                         (.begin bb VertexFormat$Mode/QUADS DefaultVertexFormat/POSITION_TEX)
                         ;; Vertices matching upstream drawLine: 0,0→1,1 UV stretched
                         (.vertex bb pose-matrix (float (- x0 nx)) (float (- y0 ny)) 0.0) (.uv bb (float 0.0) (float 0.0)) (.endVertex bb)
                         (.vertex bb pose-matrix (float (+ x0 nx)) (float (+ y0 ny)) 0.0) (.uv bb (float 0.0) (float 1.0)) (.endVertex bb)
                         (.vertex bb pose-matrix (float (+ x1 nx)) (float (+ y1 ny)) 0.0) (.uv bb (float 1.0) (float 1.0)) (.endVertex bb)
                         (.vertex bb pose-matrix (float (- x1 nx)) (float (- y1 ny)) 0.0) (.uv bb (float 1.0) (float 0.0)) (.endVertex bb)
                         (BufferUploader/drawWithShader (.end bb))
                         (RenderSystem/setShaderColor 1.0 1.0 1.0 1.0))))))
    ;; --- Shader-based progress ring (skill-progbar shader) ---
    ;; Uses BufferBuilder to avoid blit() which overrides custom shaders.
    :shader-progress-ring
    (let [^ShaderInstance si (platform-bridge/resolve-shader (:shader-id op))
          tex-0-key (:texture-0 op)
          tex-1-key (:texture-1 op)
          loc-0 (if (keyword? tex-0-key) (get-skill-tree-texture tex-0-key)
                    (path->resource-location tex-0-key))
          loc-1 (when tex-1-key
                  (if (keyword? tex-1-key) (get-skill-tree-texture tex-1-key)
                      (path->resource-location tex-1-key)))
          progress (float (or (:progress op) 0.0))]
      (if (and si loc-0)
        (do
          (.setSampler si "TexSampler0" loc-0)
          (when loc-1 (.setSampler si "TexSampler1" loc-1))
          (RenderSystem/setShader (reify java.util.function.Supplier (get [_] si)))
          (when-let [u (.safeGetUniform si "Progress")]
            (.set u progress))
          (render-custom-shader-quad! graphics poseStack si loc-0 loc-1
                                      (double (:x op)) (double (:y op))
                                      (double (:w op)) (double (:h op)))
          (RenderSystem/setShader (reify java.util.function.Supplier (get [_] nil))))
        (if loc-0
          (.blit graphics loc-0 (:x op) (:y op) 0 0 (:w op) (:h op) (:w op) (:h op))
          (.fill graphics (:x op) (:y op) (+ (:x op) (:w op)) (+ (:y op) (:h op)) 0xFF2A2A2A))))
    ;; --- Mono shader blit (grayscale for unlearned skill icons) ---
    :shader-mono-blit
    (let [tex-key (:texture op)
          loc (if (keyword? tex-key) (get-skill-tree-texture tex-key)
                  (path->resource-location tex-key))
          ^ShaderInstance si (platform-bridge/resolve-shader :mono)]
      (if (and si loc)
        (do
          (.setSampler si "TexSampler0" loc)
          (RenderSystem/setShader (reify java.util.function.Supplier (get [_] si)))
          (render-custom-shader-quad! graphics poseStack si loc nil
                                      (double (:x op)) (double (:y op))
                                      (double (:w op)) (double (:h op)))
          (RenderSystem/setShader (reify java.util.function.Supplier (get [_] nil))))
        (do
          (RenderSystem/setShaderColor 0.53 0.53 0.53 1.0)
          (.blit graphics loc (:x op) (:y op) 0 0 (:w op) (:h op) (:w op) (:h op))
          (RenderSystem/setShaderColor 1.0 1.0 1.0 1.0))))
    ;; --- Alpha-discard depth mask (writes depth from texture alpha, GL 3.2 core replacement for glAlphaFunc) ---
    :alpha-discard-depth-mask
    (let [tex-key (:texture op)
          loc (if (keyword? tex-key) (get-skill-tree-texture tex-key)
                  (path->resource-location tex-key))
          ^ShaderInstance si (platform-bridge/resolve-shader :alpha-discard)
          threshold (float (or (:alpha-threshold op) 0.3))]
      (when (and si loc)
        (.setSampler si "TexSampler0" loc)
        (when-let [u (.safeGetUniform si "AlphaThreshold")]
          (.set u threshold))
        (RenderSystem/depthMask true)
        (GL11/glColorMask false false false false)
        (RenderSystem/setShader (reify java.util.function.Supplier (get [_] si)))
        (render-custom-shader-quad! graphics poseStack si loc nil
                                    (double (:x op)) (double (:y op))
                                    (double (:w op)) (double (:h op)))
        (RenderSystem/setShader (reify java.util.function.Supplier (get [_] nil)))
        (GL11/glColorMask true true true true)))
    ;; --- Set/clear custom shader (for multi-step shader operations) ---
    :set-shader (let [^ShaderInstance si (platform-bridge/resolve-shader (:shader-id op))]
                  (when si
                    (.setSampler si "TexSampler0" (if (keyword? (:texture-0 op))
                                                    (get-skill-tree-texture (:texture-0 op))
                                                    (path->resource-location (:texture-0 op))))
                    (RenderSystem/setShader (reify java.util.function.Supplier (get [_] si)))))
    :clear-shader (RenderSystem/setShader (reify java.util.function.Supplier (get [_] nil)))
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
                        (let [^Minecraft mc (Minecraft/getInstance)
                              ^Window win (.getWindow mc)
                              w (.getGuiScaledWidth win)
                              h (.getGuiScaledHeight win)]
                          (with-client-session captured-session-id
                            #(client-ui/client-build-managed-screen-draw-ops screen-key mouse-x mouse-y (int w) (int h)))))
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

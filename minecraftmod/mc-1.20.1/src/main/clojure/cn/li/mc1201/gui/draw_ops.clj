(ns cn.li.mc1201.gui.draw-ops
  "Shared draw-ops rendering engine.
   Unified from draw_ops_host.clj (CGUI) and host.clj (managed screens).
   Renders a vector of draw-op maps into GuiGraphics.

   Does NOT preset enableBlend — callers add it when needed."
  (:require [cn.li.mc1201.client.texture-registry :as tex-registry]
            [cn.li.mc1201.gui.cgui.assets :as assets]
            [cn.li.mcmod.client.platform-bridge :as platform-bridge]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.client.gui GuiGraphics Font]
           [net.minecraft.client.renderer ShaderInstance]
           [net.minecraft.resources ResourceLocation]
           [com.mojang.blaze3d.systems RenderSystem]
           [com.mojang.blaze3d.vertex DefaultVertexFormat VertexFormat$Mode PoseStack
            Tesselator BufferBuilder BufferUploader PoseStack$Pose]
           [org.joml Matrix4f]
           [org.lwjgl.opengl GL11]
           [java.util.function Supplier]))

;; ============================================================================
;; AOT-safe static Shader Supplier (replaces reify to survive ProGuard remapping)
;; ============================================================================

(deftype StaticShaderSupplier [^ShaderInstance shader]
  Supplier
  (get [_] shader))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- path->resource-location
  "Normalize a content texture path to a ResourceLocation."
  [path]
  (assets/ensure-resource-location path))

(defn- render-custom-shader-quad!
  "Render a textured quad using a custom ShaderInstance — bypasses GuiGraphics/blit
  which forcibly sets GameRenderer/positionTexShader, overriding any custom shader.

  The caller must set the desired shader via RenderSystem/setShader BEFORE calling this,
  and restore it afterwards. Samplers must be set via .setSampler on the shader instance
  BEFORE calling setShader (MC 1.20.1 requires setSampler before setShader)."
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

(defn- draw-string-with-opts!
  "Render text with optional font-size scaling and alignment."
  [^GuiGraphics graphics op ^PoseStack poseStack]
  (let [^String text (str (:text op))
        x (int (or (:x op) 0)) y (int (or (:y op) 0))
        font-size (:font-size op 9)
        color (int (or (:color op) 0xFFFFFFFF))]
    (try
      (let [mc (net.minecraft.client.Minecraft/getInstance)
            ^Font font (.-font mc)]
        (.pushPose poseStack)
        (.translate poseStack (double x) (double y) 0.0)
        (when (not= font-size 9)
          (let [scale (/ (double font-size) 9.0)]
            (.scale poseStack (float scale) (float scale) 1.0)))
        (case (:align op)
          :center (.translate poseStack (double (/ (.width font text) -2)) 0.0 0.0)
          :right  (.translate poseStack (double (- (.width font text))) 0.0 0.0)
          nil)
        (.drawString graphics font text 0 0 color)
        (.popPose poseStack))
      (catch Exception _ nil))))

;; ============================================================================
;; Single op renderer — the core dispatch
;; ============================================================================

(declare render-ops!)  ;; forward decl for parallax-bundle self-call

(defn render-op!
  "Render a single draw-op into the given GuiGraphics.
   Extracts PoseStack from graphics internally — no allocation."
  [^GuiGraphics graphics op]
  (let [^PoseStack poseStack (.pose graphics)]
    (try
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
                         s (double (:s op))]
                     (.translate poseStack cx cy 0.0)
                     (.scale poseStack (float s) (float s) 1.0)
                     (.translate poseStack (- cx) (- cy) 0.0))
        ;; --- Depth state ---
        :enable-depth  (RenderSystem/enableDepthTest)
        :disable-depth (RenderSystem/disableDepthTest)
        :depth-mask    (RenderSystem/depthMask (boolean (:write? op true)))
        :depth-func    (let [fmap {:equal   GL11/GL_EQUAL
                                   :lequal  GL11/GL_LEQUAL
                                   :notequal GL11/GL_NOTEQUAL}]
                         (when-let [gf (fmap (:func op))]
                           (GL11/glDepthFunc gf)))
        ;; --- Blend / alpha ---
        :enable-blend  (do (RenderSystem/enableBlend) (RenderSystem/defaultBlendFunc))
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
        :fill (let [x (int (:x op)) y (int (:y op))
                    w (int (:w op)) h (int (:h op))
                    color (int (or (:color op) 0xFF000000))]
                (.fill graphics x y (+ x w) (+ y h) color))
        ;; --- Textures ---
        :icon-or-fill (let [loc (if (keyword? (:texture op))
                                  (tex-registry/resolve-texture (:texture op))
                                  (path->resource-location (:texture op)))]
                        (if loc
                          (.blit graphics loc (int (:x op)) (int (:y op)) 0 0
                            (int (:w op)) (int (:h op)) (int (:w op)) (int (:h op)))
                          (.fill graphics (int (:x op)) (int (:y op))
                            (+ (int (:x op)) (int (:w op))) (+ (int (:y op)) (int (:h op)))
                            (int (or (:fallback-color op) 0xFF2A2A2A)))))
        :textured-quad (let [tex-key (:texture op)
                             loc (if (keyword? tex-key)
                                   (tex-registry/resolve-texture tex-key)
                                   (path->resource-location tex-key))]
                         (when (and (not (keyword? tex-key)) (nil? (path->resource-location tex-key)))
                           (log/error "[draw-ops] textured-quad FAILED to resolve:" (pr-str tex-key)))
                         (when loc
                           (.blit graphics loc (int (:x op)) (int (:y op)) 0 0
                             (int (:w op)) (int (:h op)) (int (:w op)) (int (:h op)))))
        :raw-rect-uv   (let [tex-key (:texture op)
                             ^ResourceLocation loc (if (keyword? tex-key)
                                                     (tex-registry/resolve-texture tex-key)
                                                     (path->resource-location tex-key))]
                         (when loc
                           (platform-bridge/blit-textured-quad! graphics loc
                             (float (:x op)) (float (:y op))
                             (float (+ (:x op) (:w op))) (float (+ (:y op) (:h op)))
                             0.0
                             (float (or (:min-u op) 0.0)) (float (or (:max-u op) 1.0))
                             (float (or (:min-v op) 0.0)) (float (or (:max-v op) 1.0)))))
        ;; --- Connection line (textured quad) ---
        :line-quad (let [^ResourceLocation tex (tex-registry/resolve-texture :tex-line)
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
        ;; --- Shader-based progress ring ---
        :shader-progress-ring
        (let [^ShaderInstance si (platform-bridge/resolve-shader (:shader-id op))
              tex-0-key (:texture-0 op) tex-1-key (:texture-1 op)
              loc-0 (if (keyword? tex-0-key)
                      (tex-registry/resolve-texture tex-0-key)
                      (path->resource-location tex-0-key))
              loc-1 (when tex-1-key
                      (if (keyword? tex-1-key)
                        (tex-registry/resolve-texture tex-1-key)
                        (path->resource-location tex-1-key)))
              progress (float (or (:progress op) 0.0))]
          (if (and si loc-0)
            (do
              (.setSampler si "TexSampler0" loc-0)
              (when loc-1 (.setSampler si "TexSampler1" loc-1))
              (RenderSystem/setShader (StaticShaderSupplier. si))
              (when-let [u (.safeGetUniform si "Progress")]
                (.set u progress))
              (render-custom-shader-quad! graphics poseStack si loc-0 loc-1
                                          (double (:x op)) (double (:y op))
                                          (double (:w op)) (double (:h op)))
              (RenderSystem/setShader (StaticShaderSupplier. nil)))
            (when loc-0
              (.blit graphics loc-0 (int (:x op)) (int (:y op)) 0 0
                (int (:w op)) (int (:h op)) (int (:w op)) (int (:h op))))))
        ;; --- Mono shader blit (grayscale for unlearned content icons) ---
        :shader-mono-blit
        (let [tex-key (:texture op)
              loc (if (keyword? tex-key)
                    (tex-registry/resolve-texture tex-key)
                    (path->resource-location tex-key))
              ^ShaderInstance si (platform-bridge/resolve-shader :mono)]
          (if (and si loc)
            (do
              (.setSampler si "TexSampler0" loc)
              (RenderSystem/setShader (StaticShaderSupplier. si))
              (render-custom-shader-quad! graphics poseStack si loc nil
                                          (double (:x op)) (double (:y op))
                                          (double (:w op)) (double (:h op)))
              (RenderSystem/setShader (StaticShaderSupplier. nil)))
            (do
              (RenderSystem/setShaderColor 0.53 0.53 0.53 1.0)
              (.blit graphics loc (int (:x op)) (int (:y op)) 0 0
                (int (:w op)) (int (:h op)) (int (:w op)) (int (:h op)))
              (RenderSystem/setShaderColor 1.0 1.0 1.0 1.0))))
        ;; --- Alpha-discard depth mask (writes depth from texture alpha) ---
        :alpha-discard-depth-mask
        (let [tex-key (:texture op)
              loc (if (keyword? tex-key)
                    (tex-registry/resolve-texture tex-key)
                    (path->resource-location tex-key))
              ^ShaderInstance si (platform-bridge/resolve-shader :alpha-discard)
              threshold (float (or (:alpha-threshold op) 0.3))]
          (when (and si loc)
            (.setSampler si "TexSampler0" loc)
            (when-let [u (.safeGetUniform si "AlphaThreshold")]
              (.set u threshold))
            (RenderSystem/depthMask true)
            (GL11/glColorMask false false false false)
            (RenderSystem/setShader (StaticShaderSupplier. si))
            (render-custom-shader-quad! graphics poseStack si loc nil
                                        (double (:x op)) (double (:y op))
                                        (double (:w op)) (double (:h op)))
            (RenderSystem/setShader (StaticShaderSupplier. nil))
            (GL11/glColorMask true true true true)))
        ;; --- Set/clear custom shader (for multi-step shader operations) ---
        :set-shader (let [^ShaderInstance si (platform-bridge/resolve-shader (:shader-id op))]
                      (when si
                        (.setSampler si "TexSampler0"
                         (if (keyword? (:texture-0 op))
                           (tex-registry/resolve-texture (:texture-0 op))
                           (path->resource-location (:texture-0 op))))
                        (RenderSystem/setShader (StaticShaderSupplier. si))))
        :clear-shader (RenderSystem/setShader (StaticShaderSupplier. nil))
        ;; --- Progress ring (non-shader fallback, circle segments) ---
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
        ;; --- CGUI parallax bundle (background + pre-ops + tree-ops) ---
        :parallax-bundle
        (let [bg-u (float (or (:bg-u op) 0.5))
              bg-v (float (or (:bg-v op) 0.5))
              bg-scale-inv (double (or (:bg-scale-inv op) 0.99))
              node-dx (double (or (:node-dx op) 0.0))
              node-dy (double (or (:node-dy op) 0.0))
              pre-ops (:pre-ops op)
              tree-ops (:tree-ops op)]
          ;; BG layer: parallax-UV background quad
          (when-let [loc (tex-registry/resolve-texture :bg-area)]
            (platform-bridge/blit-textured-quad! graphics loc
              0.0 0.0 420.0 260.0 0.0
              bg-u (float (+ bg-u bg-scale-inv))
              bg-v (float (+ bg-v bg-scale-inv))))
          ;; Layer 1: pre-tree (static, no parallax)
          (when (seq pre-ops) (render-ops! graphics pre-ops))
          ;; Layer 2: tree (with parallax translate)
          (when (seq tree-ops)
            (.pushPose poseStack)
            (.translate poseStack node-dx node-dy 0.0)
            (render-ops! graphics tree-ops)
            (.popPose poseStack)))
        ;; --- Unknown op: no-op ---
        nil)
      (catch Exception e
        (log/debug "[draw-ops] render-op error:" (.getMessage e))))))

;; ============================================================================
;; Multi-op renderer — iterates a vector of draw ops
;; ============================================================================

(defn render-ops!
  "Render a vector of draw ops into the given GuiGraphics.
   Dispatches each op through render-op!."
  [^GuiGraphics graphics ops]
  (doseq [op ops]
    (render-op! graphics op)))

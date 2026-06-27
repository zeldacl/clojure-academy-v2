(ns cn.li.mc1201.gui.cgui.draw-ops-host
  "CGUI widget that hosts draw-ops rendering inside the CGUI component system.

  The widget carries a :draw-ops component whose ops-fn is called each render
  pass by the CGUI renderer (renderer.clj). The renderer passes the GuiGraphics
  from the Screen's render method, so we don't need a global accessor.

  Usage:
    (draw-ops-host! parent-area ops-fn)
  where ops-fn is a (fn [] ops-vector) that produces draw ops each frame."
  (:require [cn.li.mcmod.config :as modid-config]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.gui.events :as events]
            [cn.li.mcmod.client.platform-bridge :as platform-bridge]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.client.gui GuiGraphics Font]
           [net.minecraft.client.renderer ShaderInstance]
           [net.minecraft.resources ResourceLocation]
           [com.mojang.blaze3d.systems RenderSystem]
           [com.mojang.blaze3d.vertex DefaultVertexFormat VertexFormat$Mode PoseStack
            Tesselator BufferBuilder BufferUploader PoseStack$Pose]
           [org.joml Matrix4f]
           [org.lwjgl.opengl GL11]))

;; ============================================================================
;; Texture resolution
;; ============================================================================

(let [mod-id-str (or modid-config/*mod-id* "my_mod")]
  (def ^:private skill-tree-textures
    {:skill-back           (ResourceLocation. mod-id-str "textures/guis/developer/skill_back.png")
     :skill-outline        (ResourceLocation. mod-id-str "textures/guis/developer/skill_outline.png")
     :skill-mask           (ResourceLocation. mod-id-str "textures/guis/developer/skill_radial_mask.png")
     :skill-view-outline   (ResourceLocation. mod-id-str "textures/guis/developer/skill_view_outline.png")
     :skill-view-outline-glow (ResourceLocation. mod-id-str "textures/guis/developer/skill_view_outline_glow.png")
     :tex-line             (ResourceLocation. mod-id-str "textures/guis/developer/line.png")
     :tex-button           (ResourceLocation. mod-id-str "textures/guis/developer/button.png")
     :bg-area              (ResourceLocation. mod-id-str "textures/guis/effect/effect_developer_background.png")}))

(defn- get-skill-tree-texture [key]
  (get skill-tree-textures key))

(defn- path->resource-location [path]
  (when path
    (let [s (str path)]
      (if (.contains s ":")
        (ResourceLocation. (subs s 0 (.indexOf s ":")) (subs s (inc (.indexOf s ":"))))
        (ResourceLocation. "my_mod" (if (.startsWith s "textures/") (subs s 9) s))))))

;; ============================================================================
;; Custom shader quad (bypasses blit which overrides custom shaders)
;; ============================================================================

(defn- render-custom-shader-quad!
  [^GuiGraphics graphics ^PoseStack poseStack ^ShaderInstance si loc-0 loc-1 x y w h]
  (let [^PoseStack$Pose entry (.last poseStack)
        ^Matrix4f pose-matrix (.pose entry)
        ^Tesselator tess (Tesselator/getInstance)
        ^BufferBuilder bb (.getBuilder tess)
        x1 (float x)  y1 (float y)
        x2 (float (+ x w)) y2 (float (+ y h))
        z (float 0)
        u0 (float 0.0) v0 (float 0.0) u1 (float 1.0) v1 (float 1.0)]
    (RenderSystem/setShaderTexture 0 loc-0)
    (when loc-1
      (RenderSystem/setShaderTexture 1 loc-1))
    (.begin bb VertexFormat$Mode/QUADS DefaultVertexFormat/POSITION_TEX)
    (.vertex bb pose-matrix x1 y2 z) (.uv u0 v1) (.endVertex bb)
    (.vertex bb pose-matrix x2 y2 z) (.uv u1 v1) (.endVertex bb)
    (.vertex bb pose-matrix x2 y1 z) (.uv u1 v0) (.endVertex bb)
    (.vertex bb pose-matrix x1 y1 z) (.uv u0 v0) (.endVertex bb)
    (BufferUploader/drawWithShader (.end bb))))

;; ============================================================================
;; Draw op renderer (called by CGUI renderer.clj :draw-ops dispatch)
;; ============================================================================

(defn- draw-string-with-opts! [^GuiGraphics graphics op ^PoseStack poseStack]
  (let [^String text (str (:text op))
        x (int (or (:x op) 0)) y (int (or (:y op) 0))
        font-size (:font-size op 9)
        color (int (or (:color op) 0xFFFFFFFF))]
    (try
      (let [mc (net.minecraft.client.Minecraft/getInstance)
            ^Font font (.-font mc)
            ^PoseStack ps (or poseStack (.pose graphics))]
        (.pushPose ps)
        (.translate ps (double x) (double y) 0.0)
        (when (not= font-size 9)
          (let [scale (/ (double font-size) 9.0)]
            (.scale ps (float scale) (float scale) 1.0)))
        (when (= :center (:align op))
          (.translate ps (double (/ (.width font text) -2)) 0.0 0.0))
        (when (= :right (:align op))
          (.translate ps (double (- (.width font text))) 0.0 0.0))
        (.drawString graphics font text 0 0 color)
        (.popPose ps))
      (catch Exception _ nil))))

(defn render-ops!
  "Render a vector of draw ops into the given GuiGraphics.
   Called by the CGUI renderer each frame for :draw-ops components."
  [^GuiGraphics graphics ops]
  (let [^PoseStack poseStack (.pose graphics)]
    (RenderSystem/enableBlend)
    (doseq [op ops]
      (try
        (case (:kind op)
          :fill (let [x (int (:x op)) y (int (:y op)) w (int (:w op)) h (int (:h op))
                      color (int (or (:color op) 0xFF000000))]
                  (.fill graphics x y (+ x w) (+ y h) color))
          :text (draw-string-with-opts! graphics op poseStack)
          :enable-depth (RenderSystem/enableDepthTest)
          :disable-depth (RenderSystem/disableDepthTest)
          :enable-blend (do (RenderSystem/enableBlend) (RenderSystem/defaultBlendFunc))
          :push-pose (.pushPose poseStack)
          :pop-pose (.popPose poseStack)
          :translate (let [x (double (or (:x op) 0.0)) y (double (or (:y op) 0.0))
                           z (double (or (:z op) 0.0))]
                       (.translate poseStack x y z))
          :scale (let [cx (double (or (:cx op) 0.0)) cy (double (or (:cy op) 0.0))
                       s (double (:s op))]
                   (.translate poseStack cx cy 0.0)
                   (.scale poseStack (float s) (float s) 1.0)
                   (.translate poseStack (- cx) (- cy) 0.0))
          :depth-mask (RenderSystem/depthMask (boolean (:write? op true)))
          :depth-func (let [fmap {:equal GL11/GL_EQUAL :lequal GL11/GL_LEQUAL :notequal GL11/GL_NOTEQUAL}]
                        (when-let [gf (fmap (:func op))] (GL11/glDepthFunc gf)))
          :alpha-color (RenderSystem/setShaderColor
                         (float (or (:r op) 1.0)) (float (or (:g op) 1.0))
                         (float (or (:b op) 1.0)) (float (or (:a op) 1.0)))
          :textured-quad (let [loc (if (keyword? (:texture op)) (get-skill-tree-texture (:texture op))
                                      (path->resource-location (:texture op)))]
                           (when loc
                             (.blit graphics loc (int (:x op)) (int (:y op)) 0 0
                               (int (:w op)) (int (:h op)) (int (:w op)) (int (:h op)))))
          :icon-or-fill (let [loc (if (keyword? (:texture op)) (get-skill-tree-texture (:texture op))
                                     (path->resource-location (:texture op)))]
                          (if loc
                            (.blit graphics loc (int (:x op)) (int (:y op)) 0 0
                              (int (:w op)) (int (:h op)) (int (:w op)) (int (:h op)))
                            (.fill graphics (int (:x op)) (int (:y op))
                              (+ (int (:x op)) (int (:w op))) (+ (int (:y op)) (int (:h op)))
                              (int (or (:fallback-color op) 0xFF2A2A2A)))))
          :raw-rect-uv (let [loc (if (keyword? (:texture op)) (get-skill-tree-texture (:texture op))
                                    (path->resource-location (:texture op)))]
                         (when loc
                           (.innerBlit graphics loc (int (:x op)) (+ (int (:x op)) (int (:w op)))
                             (int (:y op)) (+ (int (:y op)) (int (:h op))) 0
                             (float (or (:min-u op) 0.0)) (float (or (:max-u op) 1.0))
                             (float (or (:min-v op) 0.0)) (float (or (:max-v op) 1.0)))))
          :line-quad (let [tex (get-skill-tree-texture :tex-line)
                           x0 (double (:x0 op)) y0 (double (:y0 op))
                           x1 (double (:x1 op)) y1 (double (:y1 op))
                           line-w (double (:line-width op 5.5))
                           color (int (or (:color op) 0xFFFFFFFF))
                           alpha (double (/ (bit-and (bit-shift-right color 24) 0xFF) 255.0))]
                       (when (and tex (pos? alpha))
                         (let [dx (- x1 x0) dy (- y1 y0) norm (Math/sqrt (+ (* dx dx) (* dy dy)))]
                           (when (pos? norm)
                             (let [half-w (/ line-w 2.0)
                                   nx (* (/ (- dy) norm) half-w) ny (* (/ dx norm) half-w)
                                   ^PoseStack$Pose entry (.last poseStack)
                                   ^Matrix4f pose-matrix (.pose entry)
                                   ^Tesselator tess (Tesselator/getInstance)
                                   ^BufferBuilder bb (.getBuilder tess)]
                               (RenderSystem/setShaderColor 1.0 1.0 1.0 (float alpha))
                               (RenderSystem/enableBlend) (RenderSystem/defaultBlendFunc)
                               (RenderSystem/setShaderTexture 0 tex)
                               (.begin bb VertexFormat$Mode/QUADS DefaultVertexFormat/POSITION_TEX)
                               (.vertex bb pose-matrix (float (- x0 nx)) (float (- y0 ny)) 0.0) (.uv 0.0 0.0) (.endVertex bb)
                               (.vertex bb pose-matrix (float (+ x0 nx)) (float (+ y0 ny)) 0.0) (.uv 0.0 1.0) (.endVertex bb)
                               (.vertex bb pose-matrix (float (+ x1 nx)) (float (+ y1 ny)) 0.0) (.uv 1.0 1.0) (.endVertex bb)
                               (.vertex bb pose-matrix (float (- x1 nx)) (float (- y1 ny)) 0.0) (.uv 1.0 0.0) (.endVertex bb)
                               (BufferUploader/drawWithShader (.end bb))
                               (RenderSystem/setShaderColor 1.0 1.0 1.0 1.0))))))
          :shader-progress-ring (let [^ShaderInstance si (platform-bridge/resolve-shader (:shader-id op))
                                      tex-0-key (:texture-0 op) tex-1-key (:texture-1 op)
                                      loc-0 (if (keyword? tex-0-key) (get-skill-tree-texture tex-0-key)
                                                (path->resource-location tex-0-key))
                                      loc-1 (when tex-1-key (if (keyword? tex-1-key) (get-skill-tree-texture tex-1-key)
                                                              (path->resource-location tex-1-key)))
                                      progress (float (or (:progress op) 0.0))]
                                  (if (and si loc-0)
                                    (do (.setSampler si "TexSampler0" loc-0)
                                        (when loc-1 (.setSampler si "TexSampler1" loc-1))
                                        (RenderSystem/setShader (reify java.util.function.Supplier (get [_] si)))
                                        (when-let [u (.safeGetUniform si "Progress")] (.set u progress))
                                        (render-custom-shader-quad! graphics poseStack si loc-0 loc-1
                                                                    (double (:x op)) (double (:y op))
                                                                    (double (:w op)) (double (:h op)))
                                        (RenderSystem/setShader (reify java.util.function.Supplier (get [_] nil))))
                                    (when loc-0 (.blit graphics loc-0 (int (:x op)) (int (:y op)) 0 0 (int (:w op)) (int (:h op)) (int (:w op)) (int (:h op))))))
          :shader-mono-blit (let [loc (if (keyword? (:texture op)) (get-skill-tree-texture (:texture op))
                                         (path->resource-location (:texture op)))
                                  ^ShaderInstance si (platform-bridge/resolve-shader :mono)]
                              (if (and si loc)
                                (do (.setSampler si "TexSampler0" loc)
                                    (RenderSystem/setShader (reify java.util.function.Supplier (get [_] si)))
                                    (render-custom-shader-quad! graphics poseStack si loc nil
                                                                (double (:x op)) (double (:y op))
                                                                (double (:w op)) (double (:h op)))
                                    (RenderSystem/setShader (reify java.util.function.Supplier (get [_] nil))))
                                (when loc (.blit graphics loc (int (:x op)) (int (:y op)) 0 0 (int (:w op)) (int (:h op)) (int (:w op)) (int (:h op))))))
          :alpha-discard-depth-mask (let [loc (if (keyword? (:texture op)) (get-skill-tree-texture (:texture op))
                                                  (path->resource-location (:texture op)))
                                          ^ShaderInstance si (platform-bridge/resolve-shader :alpha-discard)
                                          threshold (float (or (:alpha-threshold op) 0.3))]
                                      (when (and si loc)
                                        (.setSampler si "TexSampler0" loc)
                                        (when-let [u (.safeGetUniform si "AlphaThreshold")] (.set u threshold))
                                        (RenderSystem/depthMask true)
                                        (GL11/glColorMask false false false false)
                                        (RenderSystem/setShader (reify java.util.function.Supplier (get [_] si)))
                                        (render-custom-shader-quad! graphics poseStack si loc nil
                                                                    (double (:x op)) (double (:y op))
                                                                    (double (:w op)) (double (:h op)))
                                        (RenderSystem/setShader (reify java.util.function.Supplier (get [_] nil)))
                                        (GL11/glColorMask true true true true)))
          nil)
        (catch Exception e
          (log/debug "Draw-ops render-op error:" (.getMessage e)))))))

;; ============================================================================
;; DrawOpsWidget — uses CGUI component dispatch (no frame event needed)
;; ============================================================================

(defn draw-ops-host!
  "Attach a draw-ops rendering widget into `parent`.
   ops-fn is (fn [] ops-vector) called each render frame to produce draw ops.
   Returns the host widget."
  [parent ops-fn]
  (let [[pw ph] (cgui-core/get-size parent)
        widget (cgui-core/create-widget :pos [0 0] :size [pw ph])]
    (comp/add-component! widget (comp/draw-ops {:ops-fn ops-fn}))
    (cgui-core/add-widget! parent widget)
    widget))

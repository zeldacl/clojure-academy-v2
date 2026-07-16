(ns cn.li.mc1201.gui.reactive.terminal-render
  "MC-specific rendering helpers for the terminal UI (3D perspective transform
   and cursor overlay). Lives in mc-1.20.1 module because ac module does not
   have Minecraft/JOML classes on its compile classpath."
  (:require [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.client.platform-bridge :as bridge])
  (:import [cn.li.mcmod.uipojo.runtime UiRt]
           [net.minecraft.client.gui GuiGraphics]
           [net.minecraft.client Minecraft]
           [net.minecraft.resources ResourceLocation]
           [com.mojang.blaze3d.vertex PoseStack]
           [com.mojang.blaze3d.systems RenderSystem]
           [com.mojang.blaze3d.platform GlStateManager$SourceFactor
            GlStateManager$DestFactor]
           [org.joml Quaternionf]))

;; ============================================================================
;; 3D Perspective transform (matches upstream TerminalUI.draw() GL sequence)
;; ============================================================================

(def ^:private max-mx 605.0)
(def ^:private max-my 740.0)

;; Pre-allocated Quaternionf (reused every frame)
(defonce ^:private qz (Quaternionf.))
(defonce ^:private qy (Quaternionf.))
(defonce ^:private qx (Quaternionf.))

(defn apply-perspective!
  "Apply the 3D perspective PoseStack transform matching upstream
   AcademyCraft TerminalUI.draw(). Reads frame params from runtime
   user-signals set by shell-reactive.
   Called as the :on-pre-render hook."
  [^GuiGraphics gg ^UiRt rt mx my _pt]
  (let [;; Read frame state from runtime user signals
        fd (rt/user-signal rt :terminal-fd)  ;; double-array
        fi (rt/user-signal rt :terminal-fi)  ;; int-array
        _owner (rt/user-signal rt :terminal-owner)]
    (when (and fd fi)
      (let [^doubles fd fd
            ^ints fi fi
            new-bx (aget fd 2)
            new-by (aget fd 3)
            t-ms (double (System/currentTimeMillis))
            ^Minecraft mc (Minecraft/getInstance)
            aspect (/ (double (.getWidth (.getWindow mc)))
                     (double (.getHeight (.getWindow mc))))
            scale (/ 1.0 310.0)
            ^PoseStack ps (.pose gg)]
        ;; Write current mx/my for next frame delta
        (aset fd 4 (double mx)) (aset fd 5 (double my))
        ;; --- 3D Perspective transform sequence (exact upstream GL matching) ---
        (.pushPose ps)
        (.translate ps (* 0.35 aspect) 1.2 -4.0)
        (.translate ps 1.0 -1.8 0.0)
        (let [^Quaternionf qqz qz ^Quaternionf qqy qy ^Quaternionf qqx qx]
          (.identity qqz) (.rotateZ qqz (Math/toRadians -1.6)) (.mulPose ps qqz)
          (.identity qqy) (.rotateY qqy (Math/toRadians (+ -18.0 (* -4.0 (- (/ new-bx max-mx) 0.5)) (Math/sin (/ t-ms 1000.0))))) (.mulPose ps qqy)
          (.identity qqx) (.rotateX qqx (Math/toRadians (+ 7.0 (* 4.0 (- (/ new-by max-my) 0.5))))) (.mulPose ps qqx))
        (.translate ps -1.0 1.8 0.0)
        (.scale ps (float scale) (float (- scale)) (float scale))))))

;; ============================================================================
;; Cursor rendering (matches upstream TerminalUI.draw() cursor block)
;; ============================================================================

(defonce ^:private cursor-rl
  (ResourceLocation. "my_mod" "textures/guis/data_terminal/cursor.png"))

(defn render-cursor!
  "Render the custom cursor with additive blending (upstream: GL_SRC_ALPHA,
   GL_ONE, alpha 0.4). Reads buff-x/buff-y from runtime user-signals.
   Called as the :on-post-render hook."
  [^GuiGraphics gg ^UiRt rt _mx _my _pt]
  (let [fd (rt/user-signal rt :terminal-fd)]
    (when fd
      (let [^doubles fd fd
            ^PoseStack ps (.pose gg)
            bx (aget fd 2) by (aget fd 3)
            t-ms (double (System/currentTimeMillis))
            csize (+ 20.0 (* 2.0 (Math/sin (/ t-ms 300.0))))
            cx bx cy (+ by 120.0)]
        ;; Pop the PoseStack pushed in apply-perspective!
        (.popPose ps)
        ;; Draw cursor with additive blending
        (RenderSystem/enableBlend)
        (RenderSystem/blendFunc GlStateManager$SourceFactor/SRC_ALPHA
                                GlStateManager$DestFactor/ONE)
        (RenderSystem/setShaderColor 1.0 1.0 1.0 0.4)
        (let [half (/ csize 2.0)
              ix (int (- cx half)) iy (int (- cy half))
              is (int csize)]
          (.blit gg cursor-rl ix iy 0 0 is is is is))
        (RenderSystem/defaultBlendFunc)
        (RenderSystem/setShaderColor 1.0 1.0 1.0 1.0)))))

;; ============================================================================
;; Platform bridge registration (called by forge/fabric client init)
;; ============================================================================

(defn install-terminal-render-bridge!
  "Register terminal rendering ops in the platform bridge, making them
   accessible to ac module's shell-reactive via bridge/call-adapter."
  []
  (bridge/merge-client-bridge!
    {:terminal-apply-perspective! apply-perspective!
     :terminal-render-cursor!    render-cursor!}))

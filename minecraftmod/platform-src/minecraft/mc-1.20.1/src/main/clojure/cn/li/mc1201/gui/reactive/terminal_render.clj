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
           [com.mojang.blaze3d.vertex PoseStack VertexSorting]
           [com.mojang.blaze3d.systems RenderSystem]
           [com.mojang.blaze3d.platform Window GlStateManager$SourceFactor
            GlStateManager$DestFactor]
           [org.joml Matrix4f Quaternionf]
           [org.lwjgl.glfw GLFW]))

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
        render-state (rt/user-signal rt :terminal-render-state)
        _owner (rt/user-signal rt :terminal-owner)]
    (when (and fd fi render-state)
      (let [^doubles fd fd
            ^ints fi fi
            ^objects render-state render-state
            new-bx (aget fd 2)
            new-by (aget fd 3)
            t-ms (double (System/currentTimeMillis))
            ^Minecraft mc (Minecraft/getInstance)
            aspect (/ (double (.getWidth (.getWindow mc)))
                     (double (.getHeight (.getWindow mc))))
            scale (/ 1.0 310.0)
            perspective (doto (Matrix4f.)
                          (.setPerspective
                            (float (Math/toRadians 50.0))
                            (float aspect)
                            1.0
                            100.0))
            ^PoseStack ps (.pose gg)]
        ;; Write current mx/my for next frame delta
        (aset fd 4 (double mx)) (aset fd 5 (double my))
        ;; Submit normal GUI geometry before switching away from Minecraft's
        ;; orthographic projection.
        (.flush gg)
        (aset render-state 0 (RenderSystem/getProjectionMatrix))
        (RenderSystem/setProjectionMatrix
          perspective VertexSorting/DISTANCE_TO_ORIGIN)
        (RenderSystem/disableDepthTest)
        (RenderSystem/enableBlend)
        (RenderSystem/defaultBlendFunc)
        ;; --- 3D perspective + model transform (exact upstream GL matching) ---
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
  (ResourceLocation. "academy" "textures/guis/data_terminal/cursor.png"))

(defn render-cursor!
  "Render the custom cursor with additive blending (upstream: GL_SRC_ALPHA,
   GL_ONE, alpha 0.4). Reads buff-x/buff-y from runtime user-signals.
   Called as the :on-post-render hook."
  [^GuiGraphics gg ^UiRt rt _mx _my _pt]
  (let [fd (rt/user-signal rt :terminal-fd)
        fi (rt/user-signal rt :terminal-fi)
        render-state (rt/user-signal rt :terminal-render-state)]
    (when (and fd fi render-state)
      (let [^doubles fd fd
            ^ints fi fi
            ^objects render-state render-state
            ^PoseStack ps (.pose gg)
            bx (aget fd 2) by (aget fd 3)
            t-ms (double (System/currentTimeMillis))
            selected-app-idx (+ (* (aget fi 0) 3) (aget fi 1))
            selected? (and (>= selected-app-idx 0)
                           (< selected-app-idx (aget fi 3)))
            csize (* (if selected? 1.3 1.0)
                     (+ 20.0 (* 2.0 (Math/sin (/ t-ms 300.0)))))
            cx bx cy (+ by 120.0)]
        ;; Draw inside the same perspective/model transform as the terminal.
        ;; Upstream offsets the cursor quad by local z=-2.
        (.pushPose ps)
        (.translate ps 0.0 0.0 -2.0)
        (RenderSystem/enableBlend)
        (RenderSystem/blendFunc GlStateManager$SourceFactor/SRC_ALPHA
                                GlStateManager$DestFactor/ONE)
        (RenderSystem/setShaderColor 1.0 1.0 1.0 0.4)
        (let [half (/ csize 2.0)
              ix (int (- cx half)) iy (int (- cy half))
              is (int csize)]
          (.blit gg cursor-rl ix iy 0 0 is is is is))
        ;; Buffered vertices must be submitted while perspective is active.
        (.flush gg)
        (RenderSystem/defaultBlendFunc)
        (RenderSystem/setShaderColor 1.0 1.0 1.0 1.0)
        (.popPose ps)
        ;; Restore the pose pushed in apply-perspective! and the normal GUI
        ;; projection/state for anything rendered after this screen.
        (.popPose ps)
        (when-let [^Matrix4f saved-projection (aget render-state 0)]
          (RenderSystem/setProjectionMatrix
            saved-projection VertexSorting/DISTANCE_TO_ORIGIN)
          (aset render-state 0 nil))
        (RenderSystem/enableDepthTest)))))

;; ============================================================================
;; Cursor visibility (upstream hides the OS cursor and shows only the custom
;; reticle while the terminal is open; vanilla Screen otherwise leaves the
;; real system cursor visible, which would show both at once)
;; ============================================================================

(defn hide-cursor!
  "Capture unbounded mouse movement and hide the OS pointer, matching upstream
   TerminalMouseHelper while leaving only the custom reticle visible."
  []
  (let [^Minecraft mc (Minecraft/getInstance)
        ^Window w (.getWindow mc)]
    (GLFW/glfwSetInputMode (.getWindow w) GLFW/GLFW_CURSOR GLFW/GLFW_CURSOR_DISABLED)))

(defn show-cursor!
  "Called when the terminal screen closes. Restores the normal OS cursor."
  []
  (let [^Minecraft mc (Minecraft/getInstance)
        ^Window w (.getWindow mc)]
    (GLFW/glfwSetInputMode (.getWindow w) GLFW/GLFW_CURSOR GLFW/GLFW_CURSOR_NORMAL)))

;; ============================================================================
;; Platform bridge registration (called by forge/fabric client init)
;; ============================================================================

(defn install-terminal-render-bridge!
  "Register terminal rendering ops in the platform bridge, making them
   accessible to ac module's shell-reactive via bridge/call-adapter."
  []
  (bridge/merge-client-bridge!
    {:terminal-apply-perspective! apply-perspective!
     :terminal-render-cursor!    render-cursor!
     :terminal-cursor-hide!      hide-cursor!
     :terminal-cursor-show!      show-cursor!}))

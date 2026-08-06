(ns cn.li.mc1201.gui.reactive.terminal-render
  "MC-specific rendering helpers for the terminal UI.

   Upstream AcademyCraft TerminalUI is an AuxGui that draws with:
     GL_PROJECTION loadIdentity + gluPerspective(50, aspect, 1, 100)
     GL_MODELVIEW  loadIdentity + camera chain + scale(1/310,-1/310,1/310)
     cgui.draw(...)

   Hosting that camera on a modern GuiGraphics Screen still yields an empty
   frustum (panel opens, cursor hides, nothing visible). Until we have an
   AuxGui-equivalent draw pass, the terminal renders in screen orthographic
   space; apply-perspective! remains for a future overlay path."
  (:require [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.client.platform-bridge :as bridge])
  (:import [cn.li.mcmod.uipojo.runtime UiRt]
           [cn.li.mcmod.ui.node INode]
           [net.minecraft.client.gui GuiGraphics]
           [net.minecraft.client Minecraft]
           [cn.li.mcver ResourceLocations]
           [com.mojang.blaze3d.vertex PoseStack PoseStack$Pose VertexSorting]
           [com.mojang.blaze3d.systems RenderSystem]
           [com.mojang.blaze3d.platform Window GlStateManager$SourceFactor
            GlStateManager$DestFactor]
           [org.joml Matrix4f Quaternionf Matrix3f]
           [org.lwjgl.glfw GLFW]))

(def ^:private max-mx 605.0)
(def ^:private max-my 740.0)

(defonce ^:private qz (Quaternionf.))
(defonce ^:private qy (Quaternionf.))
(defonce ^:private qx (Quaternionf.))

(defn- load-identity-pose!
  [^PoseStack ps]
  (let [^PoseStack$Pose entry (.last ps)]
    (.identity ^Matrix4f (.pose entry))
    (.identity ^Matrix3f (.normal entry))))

(defn apply-perspective!
  "Upstream TerminalUI.draw() camera. Not used by the Screen host path -- kept
   for a future AuxGui-style renderer."
  [^GuiGraphics gg ^UiRt rt mx my _pt]
  (let [fd (rt/user-signal rt :terminal-fd)
        fi (rt/user-signal rt :terminal-fi)
        render-state (rt/user-signal rt :terminal-render-state)]
    (when (and fd fi render-state)
      (let [^doubles fd fd
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
        (aset fd 4 (double mx)) (aset fd 5 (double my))
        (.flush gg)
        (aset render-state 0 (RenderSystem/getProjectionMatrix))
        (RenderSystem/setProjectionMatrix
          perspective VertexSorting/DISTANCE_TO_ORIGIN)
        (RenderSystem/disableDepthTest)
        (RenderSystem/enableBlend)
        (RenderSystem/defaultBlendFunc)
        (RenderSystem/setShaderColor 1.0 1.0 1.0 1.0)
        (.pushPose ps)
        (load-identity-pose! ps)
        (.translate ps (* 0.35 aspect) 1.2 -4.0)
        (.translate ps 1.0 -1.8 0.0)
        (let [^Quaternionf qqz qz ^Quaternionf qqy qy ^Quaternionf qqx qx]
          (.identity qqz) (.rotateZ qqz (Math/toRadians -1.6)) (.mulPose ps qqz)
          (.identity qqy)
          (.rotateY qqy (Math/toRadians (+ -18.0
                                           (* -4.0 (- (/ new-bx max-mx) 0.5))
                                           (Math/sin (/ t-ms 1000.0)))))
          (.mulPose ps qqy)
          (.identity qqx)
          (.rotateX qqx (Math/toRadians (+ 7.0 (* 4.0 (- (/ new-by max-my) 0.5)))))
          (.mulPose ps qqx))
        (.translate ps -1.0 1.8 0.0)
        (.scale ps (float scale) (float (- scale)) (float scale))))))

(defonce ^:private cursor-rl
  (ResourceLocations/of "academy" "textures/guis/data_terminal/cursor.png"))

(defn render-cursor!
  "Screen-space custom reticle at panel-local (buffX, buffY+120), matching
   upstream cursor placement without the perspective camera."
  [^GuiGraphics gg ^UiRt rt _mx _my _pt]
  (let [fd (rt/user-signal rt :terminal-fd)
        fi (rt/user-signal rt :terminal-fi)]
    (when (and fd fi)
      (let [^doubles fd fd
            ^ints fi fi
            ^INode back (rt/node-by-id rt :back)
            ox (if back (.getAbsX back) 0.0)
            oy (if back (.getAbsY back) 0.0)
            sc (if back (.getCumScale back) 1.0)
            bx (aget fd 2)
            by (aget fd 3)
            t-ms (double (System/currentTimeMillis))
            selected-app-idx (+ (* (aget fi 0) 3) (aget fi 1))
            selected? (and (>= selected-app-idx 0)
                           (< selected-app-idx (aget fi 3)))
            csize (* sc (if selected? 1.3 1.0)
                     (+ 20.0 (* 2.0 (Math/sin (/ t-ms 300.0)))))
            cx (+ ox (* bx sc))
            cy (+ oy (* (+ by 120.0) sc))
            half (/ csize 2.0)
            ix (int (- cx half))
            iy (int (- cy half))
            is (int csize)]
        (RenderSystem/enableBlend)
        (RenderSystem/blendFunc GlStateManager$SourceFactor/SRC_ALPHA
                                GlStateManager$DestFactor/ONE)
        (RenderSystem/setShaderColor 1.0 1.0 1.0 0.4)
        (.blit gg cursor-rl ix iy 0 0 is is is is)
        (RenderSystem/defaultBlendFunc)
        (RenderSystem/setShaderColor 1.0 1.0 1.0 1.0)))))

(defn hide-cursor!
  []
  (let [^Minecraft mc (Minecraft/getInstance)
        ^Window w (.getWindow mc)]
    (GLFW/glfwSetInputMode (.getWindow w) GLFW/GLFW_CURSOR GLFW/GLFW_CURSOR_DISABLED)))

(defn show-cursor!
  []
  (let [^Minecraft mc (Minecraft/getInstance)
        ^Window w (.getWindow mc)]
    (GLFW/glfwSetInputMode (.getWindow w) GLFW/GLFW_CURSOR GLFW/GLFW_CURSOR_NORMAL)))

(defn install-terminal-render-bridge!
  []
  (bridge/merge-client-bridge!
    {:terminal-apply-perspective! apply-perspective!
     :terminal-render-cursor!    render-cursor!
     :terminal-cursor-hide!      hide-cursor!
     :terminal-cursor-show!      show-cursor!}))

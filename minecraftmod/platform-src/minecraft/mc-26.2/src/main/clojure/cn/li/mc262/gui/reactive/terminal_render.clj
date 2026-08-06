(ns cn.li.mc262.gui.reactive.terminal-render
  "Terminal render helpers for Minecraft 26.2's extracted GUI pipeline.

   The old global projection swap is not valid while GUI render states are
   extracted. NeoForge's picture-in-picture API cannot host an existing
   GuiGraphicsExtractor tape: its renderer only accepts 3D submit nodes.
   Rebuilding the reactive renderer around that API would also break the
   terminal host contract.

   Instead, this namespace projects the four terminal-plane corners through
   TerminalUI's complete original camera chain on the CPU, then least-squares
   fits the result to the Matrix3x2 accepted by the extractor. This includes
   the original projection, 1/310 scale, and screen placement as well as its
   pointer response and idle sway. It is still an affine fit, so straight
   lines do not converge as they do under the true perspective projection."
  (:require [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.mcmod.ui.runtime :as rt])
  (:import [cn.li.mc262.client GuiGraphicsHelper]
           [cn.li.mcmod.uipojo.runtime UiRt]
           [cn.li.mcver ResourceLocations]
           [net.minecraft.client Minecraft]
           [net.minecraft.client.gui GuiGraphicsExtractor]
           [com.mojang.blaze3d.platform Window]
           [org.joml Matrix3x2f Matrix3x2fStack]
           [org.lwjgl.glfw GLFW]))

(def ^:private max-mx 605.0)
(def ^:private max-my 740.0)
(def ^:private panel-w 640.0)
(def ^:private panel-h 785.0)
(def ^:private terminal-scale (/ 1.0 310.0))
(def ^:private fov-radians (Math/toRadians 50.0))
(defonce ^:private cursor-id
  (ResourceLocations/of "academy" "textures/guis/data_terminal/cursor.png"))

(defn- project-plane-point
  "Project a terminal design-space point through the original camera chain."
  [x y screen-w screen-h sin-x cos-x sin-y cos-y sin-z cos-z]
  (let [aspect (/ screen-w screen-h)
        ;; TerminalUI: scale(1/310, -1/310, 1/310), then translate(-1, 1.8).
        local-x (- (* terminal-scale (double x)) 1.0)
        local-y (+ (* (- terminal-scale) (double y)) 1.8)
        ;; OpenGL's Rz -> Ry -> Rx calls apply Rx, then Ry, then Rz to a
        ;; column vector; these equations follow that order exactly.
        x-after-x local-x
        y-after-x (* cos-x local-y)
        z-after-x (* sin-x local-y)
        x-after-y (+ (* cos-y x-after-x) (* sin-y z-after-x))
        y-after-y y-after-x
        z-after-y (+ (* (- sin-y) x-after-x) (* cos-y z-after-x))
        x-after-z (- (* cos-z x-after-y) (* sin-z y-after-y))
        y-after-z (+ (* sin-z x-after-y) (* cos-z y-after-y))
        ;; TerminalUI: translate(1, -1.8), then translate(.35*aspect, 1.2, -4).
        world-x (+ x-after-z 1.0 (* 0.35 aspect))
        world-y (+ y-after-z -1.8 1.2)
        world-z (+ z-after-y -4.0)
        inv-depth (/ 1.0 (- world-z))
        focal (/ 1.0 (Math/tan (/ fov-radians 2.0)))
        ndc-x (* (/ focal aspect) world-x inv-depth)
        ndc-y (* focal world-y inv-depth)]
    [(* 0.5 screen-w (+ ndc-x 1.0))
     (* 0.5 screen-h (- 1.0 ndc-y))]))

(defn- perspective-fit
  "Return the best affine fit for the CPU-projected terminal quadrilateral.

   Matrix3x2 cannot encode the quadrilateral's projective fourth degree of
   freedom. Fitting all four corners avoids privileging one edge and is much
   closer to the 1.21.1 pose than a single rotate/scale approximation."
  [screen-w screen-h angle-x angle-y angle-z]
  (let [sin-x (Math/sin angle-x) cos-x (Math/cos angle-x)
        sin-y (Math/sin angle-y) cos-y (Math/cos angle-y)
        sin-z (Math/sin angle-z) cos-z (Math/cos angle-z)
        [tl-x tl-y] (project-plane-point 0.0 0.0 screen-w screen-h
                                         sin-x cos-x sin-y cos-y sin-z cos-z)
        [tr-x tr-y] (project-plane-point panel-w 0.0 screen-w screen-h
                                         sin-x cos-x sin-y cos-y sin-z cos-z)
        [bl-x bl-y] (project-plane-point 0.0 panel-h screen-w screen-h
                                         sin-x cos-x sin-y cos-y sin-z cos-z)
        [br-x br-y] (project-plane-point panel-w panel-h screen-w screen-h
                                         sin-x cos-x sin-y cos-y sin-z cos-z)
        projected-center-x (/ (+ tl-x tr-x bl-x br-x) 4.0)
        projected-center-y (/ (+ tl-y tr-y bl-y br-y) 4.0)
        basis-xx (/ (- (+ tr-x br-x) tl-x bl-x) (* 2.0 panel-w))
        basis-xy (/ (- (+ tr-y br-y) tl-y bl-y) (* 2.0 panel-w))
        basis-yx (/ (- (+ bl-x br-x) tl-x tr-x) (* 2.0 panel-h))
        basis-yy (/ (- (+ bl-y br-y) tl-y tr-y) (* 2.0 panel-h))
        translate-x (- projected-center-x
                       (* basis-xx (/ panel-w 2.0))
                       (* basis-yx (/ panel-h 2.0)))
        translate-y (- projected-center-y
                       (* basis-xy (/ panel-w 2.0))
                       (* basis-yy (/ panel-h 2.0)))]
    (Matrix3x2f. (float basis-xx) (float basis-xy)
                 (float basis-yx) (float basis-yy)
                 (float translate-x) (float translate-y))))

(defn apply-perspective!
  "Apply a CPU-projected three-axis perspective fit to the extracted GUI.

   Unlike 1.21.1 this cannot switch the GUI projection globally, so the final
   quadrilateral is represented by its best Matrix3x2 affine fit; see the
   namespace docstring for the remaining projective difference."
  [^GuiGraphicsExtractor graphics ^UiRt runtime mx my _partial-tick]
  (let [fd (rt/user-signal runtime :terminal-fd)
        render-state (rt/user-signal runtime :terminal-render-state)]
    (when (and fd render-state)
      (let [^doubles frame-data fd
            ^objects state render-state
            pointer-x (aget frame-data 2)
            pointer-y (aget frame-data 3)
            normalized-x (- (/ pointer-x max-mx) 0.5)
            normalized-y (- (/ pointer-y max-my) 0.5)
            time-ms (double (System/currentTimeMillis))
            screen-w (max 1.0 (double (rt/screen-w runtime)))
            screen-h (max 1.0 (double (rt/screen-h runtime)))
            angle-x (Math/toRadians (+ 7.0 (* 4.0 normalized-y)))
            angle-y (Math/toRadians (+ -18.0
                                       (* -4.0 normalized-x)
                                       (Math/sin (/ time-ms 1000.0))))
            angle-z (Math/toRadians -1.6)
            ^Matrix3x2f warp (perspective-fit screen-w screen-h
                                               angle-x angle-y angle-z)
            ^Matrix3x2fStack pose (.pose graphics)]
        (aset frame-data 4 (double mx))
        (aset frame-data 5 (double my))
        (.pushMatrix pose)
        (.mul pose warp)
        (aset state 0 Boolean/TRUE)))))

(defn render-cursor!
  [^GuiGraphicsExtractor graphics ^UiRt runtime _mx _my _partial-tick]
  (let [fd (rt/user-signal runtime :terminal-fd)
        fi (rt/user-signal runtime :terminal-fi)
        render-state (rt/user-signal runtime :terminal-render-state)]
    (when (and fd fi render-state)
      (let [^doubles frame-data fd
            ^ints frame-ints fi
            ^objects state render-state
            pointer-x (aget frame-data 2)
            pointer-y (+ (aget frame-data 3) 120.0)
            selected-index (+ (* (aget frame-ints 0) 3) (aget frame-ints 1))
            selected? (and (>= selected-index 0)
                           (< selected-index (aget frame-ints 3)))
            pulse (+ 20.0 (* 2.0 (Math/sin (/ (double (System/currentTimeMillis)) 300.0))))
            size (int (* (if selected? 1.3 1.0) pulse))
            half (/ size 2)]
        ;; MOJANG_LOGO is vanilla's textured SRC_ALPHA/ONE pipeline. Submitting
        ;; through the helper preserves extraction ordering while matching the
        ;; additive 0.4-alpha cursor used by 1.21.1.
        (GuiGraphicsHelper/blitAdditive graphics cursor-id
                                       (int (- pointer-x half))
                                       (int (- pointer-y half))
                                       size size 0x66FFFFFF)
        (when (aget state 0)
          (.popMatrix ^Matrix3x2fStack (.pose graphics))
          (aset state 0 nil))))))

(defn hide-cursor! []
  (let [^Window window (.getWindow (Minecraft/getInstance))]
    (GLFW/glfwSetInputMode (.handle window) GLFW/GLFW_CURSOR GLFW/GLFW_CURSOR_DISABLED)))

(defn show-cursor! []
  (let [^Window window (.getWindow (Minecraft/getInstance))]
    (GLFW/glfwSetInputMode (.handle window) GLFW/GLFW_CURSOR GLFW/GLFW_CURSOR_NORMAL)))

(defn install-terminal-render-bridge! []
  (bridge/merge-client-bridge!
    {:terminal-apply-perspective! apply-perspective!
     :terminal-render-cursor! render-cursor!
     :terminal-cursor-hide! hide-cursor!
     :terminal-cursor-show! show-cursor!}))

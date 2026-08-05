(ns cn.li.mc262.gui.reactive.terminal-render
  "Terminal render helpers for Minecraft 26.2's extracted GUI pipeline.

   The old global projection swap is not valid while GUI render states are
   extracted. A scoped Matrix3x2 affine transform preserves the terminal's
   pointer-following motion without mutating global render state."
  (:require [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.mcmod.ui.runtime :as rt])
  (:import [cn.li.mc262.client GuiGraphicsHelper]
           [cn.li.mcmod.uipojo.runtime UiRt]
           [cn.li.mcver ResourceLocations]
           [net.minecraft.client Minecraft]
           [net.minecraft.client.gui GuiGraphicsExtractor]
           [com.mojang.blaze3d.platform Window]
           [org.joml Matrix3x2fStack]
           [org.lwjgl.glfw GLFW]))

(def ^:private max-mx 605.0)
(def ^:private max-my 740.0)
(defonce ^:private cursor-id
  (ResourceLocations/of "academy" "textures/guis/data_terminal/cursor.png"))

(defn apply-perspective!
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
            angle (float (* -0.025 normalized-x))
            scale (float (- 1.0 (* 0.025 (Math/abs normalized-y))))
            ^Matrix3x2fStack pose (.pose graphics)]
        (aset frame-data 4 (double mx))
        (aset frame-data 5 (double my))
        (.pushMatrix pose)
        (.translate pose 302.5 370.0)
        (.rotate pose angle)
        (.scale pose scale scale)
        (.translate pose -302.5 -370.0)
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
        (GuiGraphicsHelper/blit graphics cursor-id
                                (int (- pointer-x half))
                                (int (- pointer-y half))
                                size size)
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

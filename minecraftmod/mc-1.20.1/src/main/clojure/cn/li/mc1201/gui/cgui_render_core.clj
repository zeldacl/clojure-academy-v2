(ns cn.li.mc1201.gui.cgui-render-core
  "CLIENT-ONLY CGUI rendering and root sizing logic."
  (:require [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mc1201.gui.cgui-asset-loader :as cgui-asset-loader]
            [cn.li.mc1201.gui.cgui-tree-traversal :as traversal]
            [cn.li.mcmod.util.log :as log])
  (:import (net.minecraft.client Minecraft)
           (net.minecraft.client.gui GuiGraphics Font)
           (net.minecraft.client.renderer.texture TextureManager)
           (net.minecraft.resources ResourceLocation)
           (com.mojang.blaze3d.vertex PoseStack)
           (com.mojang.blaze3d.systems RenderSystem)
           (cn.li.mc1201.client MinecraftClientAccess GuiGraphicsHelper)
           (java.lang.reflect Field)
           (org.lwjgl.opengl GL11)))

(defonce ^:private texture-size-cache (atom {}))

(defn- get-texture-size-from-resource
  [resource-location]
  (cgui-asset-loader/get-texture-size-from-resource resource-location))

(defn- get-texture-size
  [resource-location]
  (when resource-location
    (let [k (str resource-location)
          cached (@texture-size-cache k)]
      (if cached
        cached
        (let [^Minecraft mc (MinecraftClientAccess/getMinecraft)
              ^TextureManager tm (try (.getTextureManager mc) (catch Exception _ nil))
              tex (try (when tm (.getTexture tm resource-location)) (catch Exception _ nil))
              size (try
                     (or
                      (get-texture-size-from-resource resource-location)
                      (when tex
                        (try
                          (let [cls (class tex)
                                ^Field wf (try (.getDeclaredField cls "width") (catch Exception _ nil))
                                ^Field hf (try (.getDeclaredField cls "height") (catch Exception _ nil))]
                            (when (and wf hf)
                              (.setAccessible wf true)
                              (.setAccessible hf true)
                              (let [w (.getInt wf tex)
                                    h (.getInt hf tex)]
                                (when (and (number? w) (number? h)) [(int w) (int h)]))))
                          (catch Exception _ nil))))
                     (catch Exception _ nil))]
          (when size (swap! texture-size-cache assoc k size))
          size)))))

(defn- apply-depth-mode!
  [mode write-depth]
  (RenderSystem/depthMask write-depth)
  (case mode
    :equals (do (RenderSystem/enableDepthTest) (RenderSystem/depthFunc GL11/GL_EQUAL))
    :always (do (RenderSystem/enableDepthTest) (RenderSystem/depthFunc GL11/GL_ALWAYS))
    (RenderSystem/disableDepthTest)))

(defn- round-int [value]
  (int (Math/round (double value))))

(defn- component-kind [c]
  (or (:kind c) (::kind c) :unknown))

(defn- kind-matches? [kind expected]
  (or (= kind expected) (= (keyword (name kind)) expected)))

(defn- component-state [c]
  (or (:state c) (atom {})))

(defn- ensure-resource-location [v]
  (cgui-asset-loader/ensure-resource-location v))

(defn resize-root!
  [root width height]
  (when root
    (let [[w h] (cgui-core/get-size root)]
      (when (and (zero? (double w)) (zero? (double h)))
        (cgui-core/set-size! root width height))
      (swap! (:metadata root) assoc :screen-size [width height])))
  root)

(defn- blit-scaled-region!
  [^GuiGraphics gg tex-loc x y target-w target-h u-px v-px src-w src-h z-level tex-atlas-w tex-atlas-h]
  (let [safe-src-w (max 1 (int src-w))
        safe-src-h (max 1 (int src-h))
        safe-atlas-w (max 1 (int tex-atlas-w))
        safe-atlas-h (max 1 (int tex-atlas-h))
        scale-x (/ (double (max 1 target-w)) (double safe-src-w))
        scale-y (/ (double (max 1 target-h)) (double safe-src-h))
        ^PoseStack ps (.pose gg)]
    (.pushPose ps)
    (when-not (zero? z-level)
      (.translate ps 0.0 0.0 (double z-level)))
    (.translate ps (double x) (double y) 0.0)
    (.scale ps (float scale-x) (float scale-y) 1.0)
    (try
      (GuiGraphicsHelper/blit9 gg tex-loc
                               (int 0) (int 0)
                               (int u-px) (int v-px)
                               (int safe-src-w) (int safe-src-h)
                               (int safe-atlas-w) (int safe-atlas-h))
      (catch Exception _
        (.blit gg tex-loc (int 0) (int 0)
               (int u-px) (int v-px)
               (int safe-src-w) (int safe-src-h))))
    (.popPose ps)))

(defn render-widget!
  [^GuiGraphics gg root [abs-x abs-y] scale left top]
  (when-not (cgui-core/visible? root)
    (throw (ex-info "render-widget! called on invisible widget" {})))
  (let [size (cgui-core/get-size root)
        [w h] size
        x (int (+ left abs-x))
        y (int (+ top abs-y))
        w-int (round-int (* (double w) scale))
        h-int (round-int (* (double h) scale))
        components @(:components root)]
    (doseq [c components]
      (let [kind (component-kind c)
            state @(component-state c)]
        (cond
          (kind-matches? kind :blendquad)
          (let [blend-tex (ensure-resource-location (:blend-tex state))
                line-tex (ensure-resource-location (:line-tex state))
                margin (double (or (:margin state) 4.0))
                color-int (unchecked-int (or (:color state) 0x80FFFFFF))
                r (/ (double (bit-and (bit-shift-right color-int 16) 0xFF)) 255.0)
                g (/ (double (bit-and (bit-shift-right color-int 8) 0xFF)) 255.0)
                b (/ (double (bit-and color-int 0xFF)) 255.0)
                a (if (pos? (bit-and color-int 0xFF000000))
                    (/ (double (bit-and (bit-shift-right color-int 24) 0xFF)) 255.0)
                    1.0)
                xs [(double (- x margin)) (double x) (double (+ x w-int)) (double (+ x w-int margin))]
                ys [(double (- y margin)) (double y) (double (+ y h-int)) (double (+ y h-int margin))]]
            (when blend-tex
              (try
                (RenderSystem/enableBlend)
                (RenderSystem/defaultBlendFunc)
                (RenderSystem/setShaderColor (float r) (float g) (float b) (float a))
                (apply-depth-mode! :none false)
                (let [tex-size (get-texture-size blend-tex)
                      tex-w (max 1 (int (or (first tex-size) 48)))
                      tex-h (max 1 (int (or (second tex-size) 48)))
                      cell-w (max 1 (int (Math/floor (/ tex-w 3.0))))
                      cell-h (max 1 (int (Math/floor (/ tex-h 3.0))))]
                  (doseq [i (range 3)
                          j (range 3)]
                    (let [x0 (round-int (nth xs i))
                          y0 (round-int (nth ys j))
                          x1 (round-int (nth xs (inc i)))
                          y1 (round-int (nth ys (inc j)))
                          tw (max 1 (- x1 x0))
                          th (max 1 (- y1 y0))
                          u (* i cell-w)
                          v (* j cell-h)]
                      (blit-scaled-region! gg blend-tex x0 y0 tw th u v cell-w cell-h 0.0 tex-w tex-h))))
                (when line-tex
                  (let [mrg 3.2
                        top-x (round-int (- x mrg))
                        top-y (round-int (- y 8.6))
                        top-w (round-int (+ w-int (* mrg 2.0)))
                        top-h (round-int 12.0)
                        bot-x (round-int (- x mrg))
                        bot-y (round-int (+ y h-int -2.0))
                        bot-w (round-int (+ w-int (* mrg 2.0)))
                        bot-h (round-int 8.0)
                        line-size (get-texture-size line-tex)
                        lw (max 1 (int (or (first line-size) 1)))
                        lh (max 1 (int (or (second line-size) 1)))]
                    (blit-scaled-region! gg line-tex top-x top-y top-w top-h 0 0 lw lh 0.0 lw lh)
                    (blit-scaled-region! gg line-tex bot-x bot-y bot-w bot-h 0 0 lw lh 0.0 lw lh)))
                (RenderSystem/setShaderColor 1.0 1.0 1.0 1.0)
                (catch Exception e
                  (log/debug "CGUI blendquad render error:" (.getMessage e))))))

          (kind-matches? kind :drawtexture)
          (let [tex-loc (ensure-resource-location (:texture state))]
            (if tex-loc
              (let [uv (:uv state)
                    tex-size (get-texture-size tex-loc)
                    tex-w (when (and tex-size (number? (first tex-size))) (int (first tex-size)))
                    tex-h (when (and tex-size (number? (second tex-size))) (int (second tex-size)))
                    has-uv? (and (sequential? uv) (>= (count uv) 4))
                    [u v uw uh] (if has-uv? uv [0 0 (or tex-w w-int) (or tex-h h-int)])
                    fractional? (and (number? u) (number? v) (number? uw) (number? uh)
                                     (<= (double u) 1.0) (<= (double v) 1.0)
                                     (<= (double uw) 1.0) (<= (double uh) 1.0))
                    basis-w (if (and tex-size (number? (first tex-size))) (first tex-size) w-int)
                    basis-h (if (and tex-size (number? (second tex-size))) (second tex-size) h-int)
                    u-px (if fractional? (round-int (* (double u) (double basis-w))) (int u))
                    v-px (if fractional? (round-int (* (double v) (double basis-h))) (int v))
                    src-w (if fractional? (round-int (* (double uw) (double basis-w))) (int uw))
                    src-h (if fractional? (round-int (* (double uh) (double basis-h))) (int uh))
                    tex-atlas-w (or tex-w src-w)
                    tex-atlas-h (or tex-h src-h)
                    color-int (or (:color state) 0xFFFFFF)
                    z-level (or (:z-level state) 0.0)
                    depth-mode (or (:depth-test-mode state) :none)
                    write-depth (and (boolean (:write-depth state)) (not= depth-mode :none))
                    r (/ (double (bit-and (bit-shift-right color-int 16) 0xFF)) 255.0)
                    g (/ (double (bit-and (bit-shift-right color-int 8) 0xFF)) 255.0)
                    b (/ (double (bit-and color-int 0xFF)) 255.0)
                    a (if (pos? (bit-and color-int 0xFF000000))
                        (/ (double (bit-and (bit-shift-right color-int 24) 0xFF)) 255.0)
                        1.0)]
                (when (and (pos? src-w) (pos? src-h))
                  (try
                    (RenderSystem/enableBlend)
                    (RenderSystem/defaultBlendFunc)
                    (RenderSystem/setShaderColor (float r) (float g) (float b) (float a))
                    (apply-depth-mode! depth-mode write-depth)
                    (blit-scaled-region! gg tex-loc x y w-int h-int u-px v-px src-w src-h z-level tex-atlas-w tex-atlas-h)
                    (RenderSystem/disableDepthTest)
                    (RenderSystem/depthFunc GL11/GL_LEQUAL)
                    (RenderSystem/depthMask true)
                    (RenderSystem/setShaderColor 1.0 1.0 1.0 1.0)
                    (catch Exception e
                      (log/debug "CGUI drawtexture render error:" (.getMessage e))))))
              (.fill gg x y (+ x w-int) (+ y h-int) (unchecked-int (or (:color state) 0xFFFFFF)))))

          (kind-matches? kind :textbox)
          (let [raw-text (str (or (:text state) ""))
                localized? (boolean (:localized? state))
                raw-text (if (and localized? (seq raw-text))
                           (try
                             (net.minecraft.client.resources.language.I18n/get raw-text (object-array 0))
                             (catch Throwable _ raw-text))
                           raw-text)
                text (if (and (:masked? state) (seq raw-text))
                       (apply str (repeat (count raw-text) \*))
                       raw-text)
                color (unchecked-int (or (:color state) 0xFFFFFF))
                ^Font font (MinecraftClientAccess/getFont)]
            (when (seq text)
              (.drawString gg font ^String text x y color))
            (when (and (:editable? state)
                       (some-> @(:metadata root) :focused?))
              (try
                (let [font (MinecraftClientAccess/getFont)
                      caret-visible? (< (mod (System/currentTimeMillis) 1000) 500)
                      caret-x (+ x (int (.width font ^String text)))]
                  (when caret-visible?
                    (.drawString gg font "|" caret-x y color)))
                (catch Exception _ nil))))

          (kind-matches? kind :progressbar)
          (let [progress (double (or (:progress state) 0.0))
                dir (:direction state :horizontal)
                color-full (unchecked-int (or (:color-full state) 0x00FF00))
                color-empty (unchecked-int (or (:color-empty state) 0x404040))]
            (if (= dir :vertical)
              (let [fill-h (round-int (* progress h-int))]
                (.fill gg x (+ y h-int (- fill-h)) (+ x w-int) (+ y h-int) color-empty)
                (.fill gg x (+ y h-int (- fill-h)) (+ x w-int) (+ y h-int) color-full))
              (let [fill-w (round-int (* progress w-int))]
                (.fill gg x y (+ x w-int) (+ y h-int) color-empty)
                (.fill gg x y (+ x fill-w) (+ y h-int) color-full))))

          (kind-matches? kind :outline)
          (let [outline-color (unchecked-int (or (:color state) 0xFFFFFF))
                width (double (or (:width state) 1.0))
                ww (int (Math/max 1.0 width))]
            (.fill gg x y (+ x w-int) (+ y ww) outline-color)
            (.fill gg x (+ y h-int (- ww)) (+ x w-int) (+ y h-int) outline-color)
            (.fill gg x y (+ x ww) (+ y h-int) outline-color)
            (.fill gg (+ x w-int (- ww)) y (+ x w-int) (+ y h-int) outline-color))

          (kind-matches? kind :tint)
          nil

          :else nil)))))

(defn render-tree!
  [^GuiGraphics gg root left top]
  (when root
    (let [visible (cgui-core/visible? root)
          size (cgui-core/get-size root)
          widgets (traversal/collect-widgets-z-ordered root [0 0] 1.0 nil)
          widget-count (count widgets)]
      (when (not (get @(:metadata root) :cgui-render-debug-logged false))
        (log/info "CGUI render-tree! root visible:" visible "size:" size "widget count:" widget-count)
        (swap! (:metadata root) assoc :cgui-render-debug-logged true))
      (doseq [[widget [abs-x abs-y] scale] widgets]
        (try
          (render-widget! gg widget [abs-x abs-y] scale left top)
          (catch Exception e
            (log/debug "CGUI render widget error:" (.getMessage e))))))))

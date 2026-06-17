(ns cn.li.mc1201.gui.cgui.font
  "CLIENT-ONLY CGui font bridge using MSDF shadow font (zero bundled assets).

  Font-size contract: :font-size N draws at N screen pixels (LambdaLib2 FontOption.fontSize).
  STB em height = 8px; scale = N / 8. Layout uses typographic bounds (no MSDF bake padding)."
  (:require [clojure.string :as str])
  (:import [net.minecraft.network.chat Component Style MutableComponent TextColor]
           [net.minecraft.client.gui Font GuiGraphics Font$DisplayMode]
           [com.mojang.blaze3d.vertex PoseStack]
           [org.joml Matrix4f]
           [cn.li.mc1201.client MinecraftClientAccess]
           [cn.li.mc1201.client.font.msdf MsdfFontManager MsdfTextFx MsdfGlyphFlags MsdfGlowAnimator]))

(defonce ^:private registry (atom {}))
(defonce ^:private msdf-base-height (atom (double MsdfFontManager/CGUI_BASE_HEIGHT)))

(defn set-msdf-base-height! [v]
  (reset! msdf-base-height (double v)))

(defn get-msdf-base-height []
  @msdf-base-height)

(defn register-font!
  "Register a CGui font keyword.

  `spec` keys:
  - :bold? — shader thickness (optional)
  - :italic? — vanilla italic style (optional)
  - :outline? / :glow? — default per-glyph effect flags when using glyph-styles API"
  [name {:keys [bold? italic? outline? glow?]}]
  (swap! registry assoc name {:bold? (boolean bold?)
                              :italic? (boolean italic?)
                              :outline? (boolean outline?)
                              :glow? (boolean glow?)})
  name)

(defn get-font [name]
  (get @registry name))

(defn font-exists? [name]
  (contains? @registry name))

(defn- msdf-active? []
  (MsdfFontManager/isAvailable))

(defn- vanilla-font ^Font []
  (MinecraftClientAccess/getFont))

(defn- shadow-font ^Font []
  (MsdfFontManager/shadowFont))

(defn- build-style ^Style [{:keys [italic?]}]
  (if italic?
    (.withItalic Style/EMPTY true)
    Style/EMPTY))

(defn- int->color-argb [color-int]
  (bit-or (bit-and color-int 0xFFFFFF) 0xFF000000))

(defn- codepoints [^String text]
  (.toArray (.codePoints text)))

(defn- segment-runs [^String text]
  (let [face (when (MsdfFontManager/hasFontFace)
               (.face (MsdfFontManager/provider)))
        cps (codepoints text)]
    (cond
      (or (nil? face) (zero? (alength cps)))
      [{:msdf? false :text (or text "")}]

      :else
      (let [runs (reduce
                  (fn [acc cp]
                    (let [ch (Character/toString cp)
                          msdf? (.hasGlyph face cp)]
                      (if (empty? acc)
                        [{:msdf? msdf? :text ch}]
                        (let [last-run (peek acc)
                              same? (= msdf? (:msdf? last-run))]
                          (if same?
                            (conj (pop acc) (update last-run :text str ch))
                            (conj acc {:msdf? msdf? :text ch}))))))
                  []
                  (map #(aget cps %) (range (alength cps))))]
        (if (seq runs) runs [{:msdf? false :text text}])))))

(defn- scale-factor [font-size]
  (* (/ (double font-size) (get-msdf-base-height)) 1.0))

(defn- component-for-run [^String text font-desc]
  (let [^MutableComponent c (Component/literal (or text ""))]
    (.withStyle c ^Style (build-style (or font-desc {})))))

(defn- component-with-glyph-styles
  "Build a Component with per-codepoint MSDF flags encoded in vertex color blue bits."
  [^String text base-color glyph-styles ^long start-offset]
  (let [cps (codepoints text)
        n (alength cps)
        base-rgb (bit-and (unchecked-int base-color) 0xFFFFFF)]
    (loop [i 0 acc (Component/empty)]
      (if (>= i n)
        acc
        (let [global-idx (+ start-offset i)
              style-map (cond
                          (map? glyph-styles) glyph-styles
                          :else (nth glyph-styles global-idx {}))
              rgb (MsdfGlyphFlags/encodeRgb
                    base-rgb
                    (boolean (:bold? style-map))
                    (boolean (:outline? style-map))
                    (boolean (:glow? style-map)))
              ^MutableComponent lit (Component/literal (Character/toString (aget cps i)))
              styled (.withStyle lit (.withColor Style/EMPTY (TextColor/fromRgb rgb)))]
          (recur (inc i) (.append acc styled)))))))

(defn- component-for-msdf-run [^String text font-desc base-color glyph-styles start-offset]
  (if glyph-styles
    (component-with-glyph-styles text base-color glyph-styles start-offset)
    (component-for-run text font-desc)))

(defn- draw-run! [^GuiGraphics gg ^Font font ^Component comp x y color shadow?]
  (let [^PoseStack ps (.pose gg)
        matrix (.pose (.last ps))
        buffer (.bufferSource gg)
        display Font$DisplayMode/NORMAL
        light 15728880]
    (.drawInBatch font comp (float x) (float y) (int color) (boolean shadow?)
                  ^Matrix4f matrix buffer display 0 light)))

(defn- segmented-width [font-desc ^String text & {:keys [glyph-styles]}]
  (let [^Font shadow (shadow-font)
        ^Font vanilla (vanilla-font)
        base-color 0xFFFFFF]
    (loop [runs (segment-runs text) cp-offset 0 width 0.0]
      (if (empty? runs)
        width
        (let [{:keys [msdf? text]} (first runs)
              ^Component comp (if msdf?
                                (component-for-msdf-run text font-desc base-color glyph-styles cp-offset)
                                (component-for-run text font-desc))
              ^Font font (if msdf? shadow vanilla)
              w (double (.width font comp))]
          (recur (rest runs) (+ cp-offset (.length text)) (+ width w)))))))

(defn text-width
  ([font-desc ^String text font-size]
   (text-width font-desc text font-size nil))
  ([font-desc ^String text font-size glyph-styles]
   (let [scale (scale-factor font-size)]
     (cond
       (str/blank? text) 0.0
       (or (msdf-active?) (MsdfFontManager/hasFontFace))
       (* scale (segmented-width font-desc text :glyph-styles glyph-styles))

       :else
       (* (double (.width (vanilla-font) (Component/literal text))) scale)))))

(defn- aligned-x [align x total-w]
  (case align
    :center (- (double x) (/ total-w 2.0))
    :right (- (double x) total-w)
    (double x)))

(defn- draw-vanilla-run! [^GuiGraphics gg font-desc ^String text x y font-size color shadow?]
  (let [^Font mc-font (vanilla-font)
        ^Component comp (component-for-run text font-desc)
        scale (float (scale-factor font-size))
        ^PoseStack ps (.pose gg)]
    (.pushPose ps)
    (try
      (.translate ps (double x) (double y) 0.0)
      (.scale ps scale scale 1.0)
      (.drawString gg mc-font comp 0 0 (int color) (boolean shadow?))
      (finally
        (.popPose ps)))))

(defn- draw-msdf-runs! [^GuiGraphics gg font-desc ^String text x y font-size color shadow?
                       & {:keys [glyph-styles]}]
  (let [use-per-glyph? (some? glyph-styles)
        bold? (and (not use-per-glyph?) (boolean (:bold? font-desc)))
        scale (scale-factor font-size)
        color' (int->color-argb (unchecked-int color))
        ^Font shadow (shadow-font)
        ^Font vanilla (vanilla-font)
        ^PoseStack ps (.pose gg)]
    (MsdfTextFx/resetForDraw bold?)
    (.pushPose ps)
    (try
      (.translate ps (double x) (double y) 0.0)
      (.scale ps (float scale) (float scale) 1.0)
      (loop [runs (segment-runs text) dx 0.0 cp-offset 0]
        (when (seq runs)
          (let [{:keys [msdf? text]} (first runs)
                ^Component comp (if msdf?
                                  (component-for-msdf-run text font-desc color' glyph-styles cp-offset)
                                  (component-for-run text font-desc))
                ^Font font (if msdf? shadow vanilla)
                w (double (.width font comp))
                draw-color (if (and msdf? glyph-styles) 0xFFFFFFFF color')]
            (draw-run! gg font comp dx 0 draw-color shadow?)
            (recur (rest runs) (+ dx w) (+ cp-offset (.length text))))))
      (finally
        (.popPose ps)))
    (.endBatch (.bufferSource gg))))

(defn- draw-fallback-runs! [^GuiGraphics gg font-desc ^String text x y font-size color shadow?]
  "Segmented vanilla draw when MSDF face exists but shader is not ready (layout parity)."
  (let [scale (scale-factor font-size)
        color' (int->color-argb (unchecked-int color))
        ^Font vanilla (vanilla-font)
        ^PoseStack ps (.pose gg)]
    (.pushPose ps)
    (try
      (.translate ps (double x) (double y) 0.0)
      (.scale ps (float scale) (float scale) 1.0)
      (loop [runs (segment-runs text) dx 0.0]
        (when (seq runs)
          (let [{:keys [text]} (first runs)
                ^Component comp (component-for-run text font-desc)
                w (double (.width vanilla comp))]
            (draw-run! gg vanilla comp dx 0 color' shadow?)
            (recur (rest runs) (+ dx w)))))
      (finally
        (.popPose ps)))
    (.endBatch (.bufferSource gg))))

(defn draw-text!
  "Draw `text` at (`x`,`y`) with MSDF shadow font (or vanilla fallback).

  `align` is one of :left, :center, :right (relative to `x`).
  Optional `glyph-styles`: vector of `{:bold? :outline? :glow?}` per codepoint,
  or a single map applied to every glyph (per-vertex flags via shader)."
  ([^GuiGraphics gg font-desc ^String text x y font-size color align shadow?]
   (draw-text! gg font-desc text x y font-size color align shadow? nil))
  ([^GuiGraphics gg font-desc ^String text x y font-size color align shadow? glyph-styles]
   (when (seq text)
     (let [total-w (text-width font-desc text font-size glyph-styles)
           x' (aligned-x align x total-w)]
       (cond
         (msdf-active?)
         (draw-msdf-runs! gg font-desc text x' y font-size color shadow?
                          :glyph-styles glyph-styles)

         (MsdfFontManager/hasFontFace)
         (draw-fallback-runs! gg font-desc text x' y font-size color shadow?)

         :else
         (draw-vanilla-run! gg font-desc text x' y font-size color shadow?))))))

(def default-mc-font nil)

(defn set-outline! [r g b a width]
  (MsdfTextFx/setOutline (float r) (float g) (float b) (float a) (float width)))

(defn set-glow! [r g b a radius]
  (MsdfTextFx/setGlow (float r) (float g) (float b) (float a) (float radius)))

(defn set-shadow-offset! [x y]
  (MsdfTextFx/setShadowOffset (float x) (float y)))

(defn start-glow-breath!
  "Start ClientTick-driven glow radius breathing (see MsdfGlowAnimator)."
  ([base-radius amplitude]
   (start-glow-breath! base-radius amplitude 2.0))
  ([base-radius amplitude period-sec]
   (MsdfGlowAnimator/startBreathing (float base-radius) (float amplitude) (float period-sec))))

(defn stop-glow-breath! []
  (MsdfGlowAnimator/stop))

(defn glow-breath-active? []
  (MsdfGlowAnimator/isActive))

(defmacro with-text-fx
  "Apply MSDF text effects for `body`, then reset uniforms.

  `fx` keys: :outline [r g b a width], :glow [r g b a radius],
  :shadow-offset [x y], :thickness float."
  [[{:keys [outline glow shadow-offset thickness]}] & body]
  `(try
     ~(when outline `(set-outline! ~@(map float outline)))
     ~(when glow `(set-glow! ~@(map float glow)))
     ~(when shadow-offset `(set-shadow-offset! ~@(map float shadow-offset)))
     ~(when thickness `(MsdfTextFx/setThicknessOffset (float ~thickness)))
     (do ~@body)
     (finally
       (MsdfTextFx/resetForDraw false))))

(defn get-cgui-font-base-height []
  (get-msdf-base-height))

(defn get-fallback-scale-factor []
  1.0)

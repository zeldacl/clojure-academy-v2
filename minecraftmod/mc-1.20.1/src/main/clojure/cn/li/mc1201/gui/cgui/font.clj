(ns cn.li.mc1201.gui.cgui.font
  "CLIENT-ONLY CGui font bridge using MSDF shadow font (zero bundled assets).

  CGui keywords (:ac-normal, :ac-bold, :ac-italic) map to style flags.
  Bold is shader-driven (advance-neutral); italic uses vanilla Style.withItalic."
  (:require [clojure.string :as str])
  (:import [net.minecraft.network.chat Component Style MutableComponent]
           [net.minecraft.client.gui Font GuiGraphics Font$DisplayMode]
           [com.mojang.blaze3d.vertex PoseStack]
           [org.joml Matrix4f]
           [cn.li.mc1201.client MinecraftClientAccess]
           [cn.li.mc1201.client.font.msdf MsdfFontManager MsdfTextFx]))

(defonce ^:private registry (atom {}))
(defonce ^:private msdf-base-height (atom 8.0))

(defn set-msdf-base-height! [v]
  (reset! msdf-base-height (double v)))

(defn get-msdf-base-height []
  @msdf-base-height)

(defn register-font!
  "Register a CGui font keyword.

  `spec` keys:
  - :bold? — shader thickness (optional)
  - :italic? — vanilla italic style (optional)"
  [name {:keys [bold? italic?]}]
  (swap! registry assoc name {:bold? (boolean bold?) :italic? (boolean italic?)})
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

(defn- draw-run! [^GuiGraphics gg ^Font font ^Component comp x y color shadow?]
  (let [^PoseStack ps (.pose gg)
        matrix (.pose (.last ps))
        buffer (.bufferSource gg)
        display Font$DisplayMode/NORMAL
        light 15728880]
    (.drawInBatch font comp (float x) (float y) (int color) (boolean shadow?)
                  ^Matrix4f matrix buffer display 0 light)))

(defn- segmented-width [font-desc ^String text]
  (let [^Font shadow (shadow-font)
        ^Font vanilla (vanilla-font)
        run-widths (map (fn [{:keys [msdf? text]}]
                          (let [^Component comp (component-for-run text font-desc)
                                ^Font font (if msdf? shadow vanilla)]
                            (double (.width font comp))))
                        (segment-runs text))]
    (reduce + 0.0 run-widths)))

(defn text-width [font-desc ^String text font-size]
  (let [scale (scale-factor font-size)]
    (cond
      (str/blank? text) 0.0
      (or (msdf-active?) (MsdfFontManager/hasFontFace))
      (* scale (segmented-width font-desc text))

      :else
      (* (double (.width (vanilla-font) (Component/literal text))) scale))))

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

(defn- draw-msdf-runs! [^GuiGraphics gg font-desc ^String text x y font-size color shadow?]
  (let [bold? (boolean (:bold? font-desc))
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
      (loop [runs (segment-runs text) dx 0.0]
        (when (seq runs)
          (let [{:keys [msdf? text]} (first runs)
                ^Component comp (component-for-run text font-desc)
                ^Font font (if msdf? shadow vanilla)
                w (double (.width font comp))]
            (draw-run! gg font comp dx 0 color' shadow?)
            (recur (rest runs) (+ dx w)))))
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

  `align` is one of :left, :center, :right (relative to `x`)."
  [^GuiGraphics gg font-desc ^String text x y font-size color align shadow?]
  (when (seq text)
    (let [total-w (text-width font-desc text font-size)
          x' (aligned-x align x total-w)]
      (cond
        (msdf-active?)
        (draw-msdf-runs! gg font-desc text x' y font-size color shadow?)

        (MsdfFontManager/hasFontFace)
        (draw-fallback-runs! gg font-desc text x' y font-size color shadow?)

        :else
        (draw-vanilla-run! gg font-desc text x' y font-size color shadow?)))))

(def default-mc-font nil)

(defn set-outline! [r g b a width]
  (MsdfTextFx/setOutline (float r) (float g) (float b) (float a) (float width)))

(defn set-glow! [r g b a radius]
  (MsdfTextFx/setGlow (float r) (float g) (float b) (float a) (float radius)))

(defn set-shadow-offset! [x y]
  (MsdfTextFx/setShadowOffset (float x) (float y)))

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

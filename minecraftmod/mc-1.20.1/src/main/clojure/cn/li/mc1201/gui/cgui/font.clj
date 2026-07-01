(ns cn.li.mc1201.gui.cgui.font
  "CLIENT-ONLY CGui font bridge using MSDF shadow font (zero bundled assets).

  Font-size contract: :font-size N draws at N screen pixels.
  STB em height = DESIGN_PIXEL_HEIGHT (32 px); scale = N / 32."
  (:require [clojure.string :as str]
            [cn.li.mc1201.client.font.msdf-setup :as msdf-setup])
  (:import [net.minecraft.network.chat Component Style MutableComponent]
           [net.minecraft.client.gui Font GuiGraphics]
           [com.mojang.blaze3d.vertex PoseStack]
           [com.mojang.blaze3d.systems RenderSystem]
           [cn.li.mc1201.client MinecraftClientAccess]
           [cn.li.mc1201.client.font.msdf MsdfFontManager]))

(defonce ^:private registry (atom {}))
(defonce ^:private msdf-base-height (atom nil))

(defn- msdf-default-base-height []
  (double MsdfFontManager/CGUI_BASE_HEIGHT))

(defn set-msdf-base-height! [v]
  (reset! msdf-base-height (double v)))

(defn get-msdf-base-height []
  (or @msdf-base-height
      (reset! msdf-base-height (msdf-default-base-height))))

(defn register-font!
  "Register a CGui font keyword.
  `spec` keys: :bold? — italic style (optional), :monospace? — fixed-width mode"
  [name {:keys [bold? italic? monospace?]}]
  (swap! registry assoc name {:bold? (boolean bold?)
                              :italic? (boolean italic?)
                              :monospace? (boolean monospace?)})
  name)

(defn get-font [name]
  (get @registry name))

(defn font-exists? [name]
  (contains? @registry name))

;; ---- helpers ----

(defn- ensure-msdf-ready! []
  (msdf-setup/ensure-ready!))

(defn- msdf-active? []
  (ensure-msdf-ready!)
  (MsdfFontManager/isAvailable))

(defn- vanilla-font ^Font []
  (MinecraftClientAccess/getFont))

(defn- shadow-font ^Font []
  (MsdfFontManager/shadowFont))

(defn- build-style ^Style [{:keys [italic?]}]
  (if italic?
    (.withItalic Style/EMPTY true)
    Style/EMPTY))

(def ^:private argb-alpha-mask -16777216)

(defn normalize-color-int
  [color-int]
  (unchecked-int
   (bit-or (bit-and (long (or color-int 0)) 0xFFFFFF) argb-alpha-mask)))

(defn- codepoints [^String text]
  (.toArray (.codePoints text)))

(defn- msdf-glyph? [cp]
  (MsdfFontManager/hasGlyph (int cp)))

(defn- segment-runs [^String text]
  (let [^ints cps (codepoints text)]
    (cond
      (or (not (MsdfFontManager/hasFontFace)) (zero? (alength cps)))
      [{:msdf? false :text (or text "")}]
      :else
      (let [runs (reduce
                  (fn [acc cp]
                    (let [ch (Character/toString (int cp))
                          msdf? (msdf-glyph? cp)]
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

;; ---- MSDF shader reference (set by ForgeClientRenderRegistry / FabricClientRenderSetup) ----

(defonce ^:private msdf-shader (atom nil))

(defn set-msdf-shader! [shader]
  (reset! msdf-shader shader))

;; ---- draw ----

(defn- draw-run! [^GuiGraphics gg ^Font font ^Component comp x y color shadow?]
  (RenderSystem/enableBlend)
  (RenderSystem/defaultBlendFunc)
  (RenderSystem/disableDepthTest)
  (RenderSystem/setShaderColor 1.0 1.0 1.0 1.0)
  (when-let [shader @msdf-shader]
    (RenderSystem/setShader (fn [] shader)))
  (.drawString gg font comp (int x) (int y) (unchecked-int color) (boolean shadow?)))

;; ---- width ----

(defn- with-monospace [monospace? body-fn]
  (MsdfFontManager/setMonospace (boolean monospace?))
  (try (body-fn)
       (finally (MsdfFontManager/setMonospace false))))

(defn- segmented-width [font-desc ^String text & {:keys [glyph-styles]}]
  (let [monospace? (boolean (:monospace? font-desc))
        ^Font shadow (shadow-font)
        ^Font vanilla (vanilla-font)
        use-shadow? (MsdfFontManager/hasFontFace)]
    (loop [runs (segment-runs text) width 0.0]
      (if (empty? runs)
        width
        (let [{:keys [msdf? text]} (first runs)
              ^Component comp (component-for-run text font-desc)
              ^Font font (if use-shadow? shadow vanilla)
              w (double
                 (if (and use-shadow? msdf? monospace?)
                   (* (count text) (MsdfFontManager/monospaceAdvance))
                   (.width font comp)))]
          (recur (rest runs) (+ width w)))))))

(defn text-width
  ([font-desc ^String text font-size]
   (text-width font-desc text font-size nil))
  ([font-desc ^String text font-size glyph-styles]
   (ensure-msdf-ready!)
   (let [scale (scale-factor font-size)]
     (cond
       (str/blank? text) 0.0
       (or (msdf-active?) (MsdfFontManager/hasFontFace))
       (with-monospace (boolean (:monospace? font-desc))
         #(* scale (segmented-width font-desc text :glyph-styles glyph-styles)))
       :else
       (* (double (.width (vanilla-font) (Component/literal text))) scale)))))

;; ---- draw-msdf-runs! ----

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
      (.drawString gg mc-font comp 0 0 (unchecked-int color) (boolean shadow?))
      (finally
        (.popPose ps)))))

(defn- draw-msdf-runs! [^GuiGraphics gg font-desc ^String text x y font-size color shadow?
                       & {:keys [glyph-styles]}]
  (let [scale (scale-factor font-size)
        ^Font shadow (shadow-font)
        ^Font vanilla (vanilla-font)
        use-shadow? (or (msdf-active?) (MsdfFontManager/hasFontFace))
        ^PoseStack ps (.pose gg)]
    (.pushPose ps)
    (try
      (.translate ps (double x) (double y) 0.0)
      (.scale ps (float scale) (float scale) 1.0)
      (loop [runs (segment-runs text) dx 0.0]
        (when (seq runs)
          (let [{:keys [msdf? text]} (first runs)
                ^Component comp (component-for-run text font-desc)
                ^Font font (if use-shadow? shadow vanilla)
                w (double (.width font comp))]
            (draw-run! gg font comp dx 0 color shadow?)
            (recur (rest runs) (+ dx w)))))
      (finally
        (.popPose ps)))
    (.endBatch (.bufferSource gg))))

(defn- draw-fallback-runs! [^GuiGraphics gg font-desc ^String text x y font-size color shadow?
                       & {:keys [glyph-styles]}]
  (draw-msdf-runs! gg font-desc text x y font-size color shadow? :glyph-styles glyph-styles))

(defn draw-text!
  "Draw `text` at (`x`,`y`) with MSDF shadow font (or vanilla fallback).
  `align` is :left, :center, or :right (relative to `x`)."
  ([^GuiGraphics gg font-desc ^String text x y font-size color align shadow?]
   (draw-text! gg font-desc text x y font-size color align shadow? nil))
  ([^GuiGraphics gg font-desc ^String text x y font-size color align shadow? glyph-styles]
   (when (seq text)
     (ensure-msdf-ready!)
     (let [total-w (text-width font-desc text font-size glyph-styles)
           x' (aligned-x align x total-w)]
       (with-monospace (boolean (:monospace? font-desc))
         #(cond
            (msdf-active?)
            (draw-msdf-runs! gg font-desc text x' y font-size color shadow?
                             :glyph-styles glyph-styles)
            (MsdfFontManager/hasFontFace)
            (draw-fallback-runs! gg font-desc text x' y font-size color shadow?
                                :glyph-styles glyph-styles)
            :else
            (draw-vanilla-run! gg font-desc text x' y font-size color shadow?)))))))

(def default-mc-font nil)

(defn get-cgui-font-base-height []
  (get-msdf-base-height))

(defn get-fallback-scale-factor []
  1.0)

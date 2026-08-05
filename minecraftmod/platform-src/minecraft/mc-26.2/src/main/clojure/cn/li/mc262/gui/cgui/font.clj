(ns cn.li.mc262.gui.cgui.font
  "CLIENT-ONLY CGui font bridge for Minecraft 26.2.

   Minecraft 26.2's text extractor owns glyph render-state submission. This
   adapter therefore uses the vanilla Font directly instead of carrying the
   removed ShaderInstance/GlyphInfo MSDF pipeline.

   When `:monospace?` is set, glyphs are measured and drawn with a fixed
   advance (width of `0`) so terminal/CGui layouts stay columnar without MSDF."
  (:require [clojure.string :as str])
  (:import [cn.li.mcbase.client MinecraftClientAccess]
           [net.minecraft.client.gui Font GuiGraphicsExtractor]
           [net.minecraft.network.chat Component MutableComponent Style]
           [org.joml Matrix3x2fStack]))

(def ^:private DEFAULT-BASE-HEIGHT 32.0)
(defonce ^:private registry (atom {}))
(defonce ^:private base-height (atom DEFAULT-BASE-HEIGHT))

(defn set-msdf-base-height!
  "Compatibility name retained for content font-size configuration."
  [value]
  (reset! base-height (double value)))

(defn get-msdf-base-height []
  @base-height)

(defn register-font!
  [name {:keys [bold? italic? monospace?]}]
  (swap! registry assoc name {:bold? (boolean bold?)
                              :italic? (boolean italic?)
                              :monospace? (boolean monospace?)})
  name)

(defn get-font [name]
  (get @registry name))

(defn font-exists? [name]
  (contains? @registry name))

(defn- vanilla-font ^Font []
  (MinecraftClientAccess/getFont))

(defn- build-style ^Style [{:keys [bold? italic?]}]
  (cond-> Style/EMPTY
    bold? (.withBold true)
    italic? (.withItalic true)))

(defn- component
  ^Component [^String text font-desc]
  (let [^MutableComponent value (Component/literal (or text ""))]
    (.withStyle value ^Style (build-style (or font-desc {})))))

(def ^:private argb-alpha-mask -16777216)

(defn normalize-color-int [color-int]
  (unchecked-int
    (bit-or (bit-and (long (or color-int 0)) 0xFFFFFF)
            argb-alpha-mask)))

(defn- scale-factor [font-size]
  (/ (double (or font-size @base-height))
     (double @base-height)))

(defn- aligned-x [align x total-width]
  (case align
    :center (- (double x) (/ total-width 2.0))
    :right (- (double x) total-width)
    (double x)))

(defn- monospace-advance
  "Unscaled em-space advance used when `:monospace?` is true."
  ^double [font-desc]
  (double (.width (vanilla-font) (component "0" font-desc))))

(defn- codepoint-count
  ^long [^String text]
  (long (.codePointCount text 0 (.length text))))

(defn- monospace-width
  ^double [font-desc ^String text]
  (* (monospace-advance font-desc) (codepoint-count text)))

(defn text-width
  ([font-desc ^String text font-size]
   (text-width font-desc text font-size nil))
  ([font-desc ^String text font-size _glyph-styles]
   (if (str/blank? text)
     0.0
     (let [scale (scale-factor font-size)
           unscaled (if (:monospace? font-desc)
                      (monospace-width font-desc text)
                      (double (.width (vanilla-font) (component text font-desc))))]
       (* unscaled scale)))))

(defn- draw-monospace!
  [^GuiGraphicsExtractor graphics font-desc ^String text
   color shadow?]
  (let [^Font font (vanilla-font)
        advance (float (monospace-advance font-desc))
        color-i (unchecked-int color)
        shadow (boolean shadow?)
        ^ints cps (.toArray (.codePoints text))
        n (alength cps)]
    (loop [i 0
           x 0.0]
      (when (< i n)
        (let [cp (aget cps i)
              ^String ch (String/valueOf (Character/toChars cp))]
          (.text graphics font (component ch font-desc) (int (Math/round x)) 0 color-i shadow)
          (recur (inc i) (+ x advance)))))))

(defn draw-text!
  "Submit vanilla text render state at (`x`,`y`) with CGui scaling/alignment."
  ([^GuiGraphicsExtractor graphics font-desc ^String text
    x y font-size color align shadow?]
   (draw-text! graphics font-desc text x y font-size color align shadow? nil))
  ([^GuiGraphicsExtractor graphics font-desc ^String text
    x y font-size color align shadow? _glyph-styles]
   (when (seq text)
     (let [width (text-width font-desc text font-size)
           draw-x (aligned-x align x width)
           scale (float (scale-factor font-size))
           ^Matrix3x2fStack pose (.pose graphics)]
       (.pushMatrix pose)
       (try
         (.translate pose (float draw-x) (float y))
         (.scale pose scale scale)
         (if (:monospace? font-desc)
           (draw-monospace! graphics font-desc text color shadow?)
           (.text graphics
                  (vanilla-font)
                  (component text font-desc)
                  0 0
                  (unchecked-int color)
                  (boolean shadow?)))
         (finally
           (.popMatrix pose)))))))

(def default-mc-font nil)

(defn get-cgui-font-base-height []
  @base-height)

(defn get-fallback-scale-factor []
  1.0)

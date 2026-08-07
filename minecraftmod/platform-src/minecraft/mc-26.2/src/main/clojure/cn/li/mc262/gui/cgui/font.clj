(ns cn.li.mc262.gui.cgui.font
  "CLIENT-ONLY CGui font bridge for Minecraft 26.2.

   Minecraft 26.2's text extractor owns glyph render-state submission. This
   adapter therefore uses the vanilla Font directly instead of carrying the
   removed ShaderInstance/GlyphInfo MSDF pipeline.

   When `:monospace?` is set, glyphs are measured and drawn with a fixed
   advance (width of `0`) so terminal/CGui layouts stay columnar without MSDF."
  (:require [clojure.string :as str])
  (:import [cn.li.mcbase.client MinecraftClientAccess]
           [cn.li.mc262.client.render GuiPerspectiveWarp]
           [net.minecraft.client.gui Font GuiGraphicsExtractor]
           [net.minecraft.network.chat Component MutableComponent Style]
           [org.joml Matrix3x2f Matrix3x2fStack]))

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

(defn- draw-warped-text!
  "Draw a run one glyph at a time, each through its own tangent affine.

   Vanilla submits a whole text run under a single Matrix3x2 pose, and a
   projective warp cannot be folded into one: linearising at the run's anchor
   drifts ~19px by the end of a 300px run, which is the same order as the
   perspective effect itself, so the text would visibly peel off the panel.
   The error grows with the square of the distance from the anchor, so
   re-anchoring per glyph — a span of ~10px instead of ~300px — puts it far
   below a pixel.  Every glyph still shares one pipeline, atlas and scissor, so
   they coalesce into the same draw."
  [^GuiGraphicsExtractor graphics ^Matrix3x2fStack pose font-desc ^String text
   draw-x y scale color shadow?]
  (let [^Font font (vanilla-font)
        color-i (unchecked-int color)
        shadow (boolean shadow?)
        glyphs (mapv #(String/valueOf (Character/toChars (int %)))
                     (.toArray (.codePoints text)))
        mono-advance (when (:monospace? font-desc) (monospace-advance font-desc))
        ;; Offsets come from prefix widths, the very measurement `text-width`
        ;; used to align the run. Summing per-glyph widths instead would round
        ;; each advance separately and let the run creep away from its own
        ;; alignment box.
        offsets (if mono-advance
                  (mapv #(* (double mono-advance) (double %)) (range (count glyphs)))
                  (mapv (fn [i]
                          (double (.width font (component (apply str (subvec glyphs 0 i))
                                                          font-desc))))
                        (range (count glyphs))))]
    (dorun
      (map (fn [^String ch offset]
             ;; Glyph origin in the caller's own space: the run's anchor plus
             ;; this glyph's advance, scaled the way the pose would be.
             ;; localAnchor folds the live pose in, so the tangent plane it
             ;; returns can replace that pose outright.
             (let [gx (+ (double draw-x) (* (double scale) (double offset)))
                   ^Matrix3x2f local (GuiPerspectiveWarp/localAnchor
                                       pose (float gx) (float y))]
               (when local
                 (.pushMatrix pose)
                 (try
                   (.set pose local)
                   (.scale pose scale scale)
                   (.text graphics font (component ch font-desc) 0 0 color-i shadow)
                   (finally
                     (.popMatrix pose))))))
           glyphs offsets))))

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
           ^Matrix3x2fStack pose (.pose graphics)
           warped? (some? (GuiPerspectiveWarp/active))]
       (if warped?
         (draw-warped-text! graphics pose font-desc text
                            draw-x y scale color shadow?)
         (do
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
               (.popMatrix pose)))))))))

(def default-mc-font nil)

(defn get-cgui-font-base-height []
  @base-height)

(defn get-fallback-scale-factor []
  1.0)

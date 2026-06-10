(ns cn.li.mc1201.gui.cgui.font
  "CLIENT-ONLY CGui font bridge using vanilla FontManager resource-pack fonts.

  Minecraft, Forge, and Fabric share the same client font pipeline (no loader-specific
  font draw API):
  - Define fonts under assets/<namespace>/font/<id>.json (ttf / reference providers).
    See https://minecraft.wiki/w/Font
  - Select a font in text via Component style `font` (e.g. my_mod:ac_normal).
  - Measure and draw with net.minecraft.client.gui.Font + GuiGraphics.drawString.

  CGui keywords (:ac-normal, :ac-bold, :ac-italic) map to font ids and style flags."
  (:import [net.minecraft.network.chat Component Style]
           [net.minecraft.resources ResourceLocation]
           [net.minecraft.client.gui Font GuiGraphics]
           [com.mojang.blaze3d.vertex PoseStack]
           [cn.li.mc1201.client MinecraftClientAccess]))

(def ^:private font-namespace "my_mod")

;; --- dynamic base-height (default 8.0 for minecraft:default bitmap) ---
;; Set to the TTF provider :size when a system font is active so that
;; :font-size N always produces N px on screen regardless of the TTF size.
(defonce ^:private cgui-font-base-height (atom 8.0))

(defn set-cgui-font-base-height! [v]
  (reset! cgui-font-base-height (double v)))

(defn get-cgui-font-base-height []
  @cgui-font-base-height)

;; ---------------------------------------------------------------------------
;; Fallback scale factor for minecraft:default bitmap font.
;; Set by font-pack-setup at runtime: 1.0 when a system TTF is active,
;; ~0.85 when the default bitmap is used (to compensate for thicker pixel
;; strokes so the visual size matches TrueType reference text).
;; ---------------------------------------------------------------------------
(defonce ^:private fallback-scale-factor (atom 1.0))

(defn set-fallback-scale-factor!
  "Update the per-frame font-scale multiplier for bitmap-fallback mode.
  Called once during client startup by font-pack-setup."
  [v]
  (reset! fallback-scale-factor (double v)))

(defn get-fallback-scale-factor
  "Return the current fallback scale factor."
  []
  @fallback-scale-factor)

(defonce ^:private registry (atom {}))

(defn- resource-location
  [path]
  (ResourceLocation. font-namespace path))

(defn register-font!
  "Register a CGui font keyword.

  `spec` keys:
  - :location — ResourceLocation or string path under font-namespace (required)
  - :bold — apply bold style when drawing (optional)
  - :italic — apply italic style when drawing (optional)"
  [name {:keys [location bold italic]}]
  (let [loc (cond
              (instance? ResourceLocation location) location
              (string? location) (resource-location location)
              :else (resource-location "ac_normal"))]
    (swap! registry assoc name {:location loc
                                :bold (boolean bold)
                                :italic (boolean italic)})
    name))

(defn get-font
  "Look up a registered font descriptor by keyword. Returns nil if not found."
  [name]
  (get @registry name))

(defn font-exists?
  [name]
  (contains? @registry name))

(defn- build-style
  [{:keys [location bold italic]}]
  (cond-> Style/EMPTY
    (instance? ResourceLocation location) (.withFont ^ResourceLocation location)
    bold (.withBold true)
    italic (.withItalic true)))

(defn text-component
  "Build a Component using the registered font descriptor (or plain text when nil)."
  (^Component [^String text font-desc]
   (if font-desc
     (.withStyle (Component/literal (or text "")) (build-style font-desc))
     (Component/literal (or text "")))))

(defn- scaled-width
  [^Font mc-font ^Component comp font-size]
  (* (double (.width mc-font comp))
     (/ (double font-size) (get-cgui-font-base-height))
     (get-fallback-scale-factor)))

(defn text-width
  "Width of `text` at CGui font-size (8pt = vanilla glyph grid)."
  [font-desc ^String text font-size]
  (scaled-width (MinecraftClientAccess/getFont)
                (text-component text font-desc)
                font-size))

(defn draw-text!
  "Draw `text` at (`x`,`y`) with vanilla Font + GuiGraphics.

  `align` is one of :left, :center, :right (relative to `x`)."
  [^GuiGraphics gg font-desc ^String text x y font-size color align shadow?]
  (when (seq text)
    (let [^Font mc-font (MinecraftClientAccess/getFont)
          ^Component comp (text-component text font-desc)
          scale (float (* (/ (double font-size) (get-cgui-font-base-height)) (get-fallback-scale-factor)))
          total-w (scaled-width mc-font comp font-size)
          x' (case align
               :center (- (double x) (/ total-w 2.0))
               :right (- (double x) total-w)
               (double x))
          color' (unchecked-int color)
          ^PoseStack ps (.pose gg)]
      (.pushPose ps)
      (try
        (.translate ps x' (double y) 0.0)
        (.scale ps scale scale 1.0)
        (.drawString gg mc-font comp 0 0 color' (boolean shadow?))
        (finally
          (.popPose ps))))))

(def default-mc-font
  "Marker for callers that intentionally use the default minecraft font."
  nil)

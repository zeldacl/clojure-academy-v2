(ns cn.li.mc1201.gui.reactive.render
  "Kind renderers (:render!/:bake!) — ported from CGUI renderer.clj zero-alloc techniques.
   All render fns take [^GuiGraphics gg ^INode node]."
  (:require [cn.li.mc1201.client.texture-registry :as tex-registry]
            [cn.li.mc1201.gui.cgui.font :as cgui-font]
            [cn.li.mc1201.gui.reactive.clock :as clock]
            [cn.li.mcmod.client.platform-bridge :as platform-bridge]
            [cn.li.mcmod.ui.node :as node]
            [cn.li.mcmod.ui.layout :as ui-layout]
            [clojure.string :as str])
  (:import [cn.li.mc1201.client GuiGraphicsHelper]
           [cn.li.mcmod.ui.node INode]
           [cn.li.mcmod.uipojo.runtime UiRt]
           [net.minecraft.client.gui GuiGraphics]
           [net.minecraft.client.renderer GameRenderer ShaderInstance]
           [net.minecraft.resources ResourceLocation]
           [com.mojang.blaze3d.vertex PoseStack PoseStack$Pose DefaultVertexFormat VertexFormat$Mode
            Tesselator BufferBuilder BufferUploader]
           [com.mojang.blaze3d.systems RenderSystem]
           [org.joml Matrix4f Quaternionf]
           [org.lwjgl.opengl GL11]))

(declare draw-tape!)

;; Slot indices matching node.clj kind definitions
(def ^:private SLOT-BOX-FILL      0)
(def ^:private SLOT-BOX-OUTLINE   1)
(def ^:private SLOT-BOX-OUTLINE-W 2)
(def ^:private SLOT-BOX-TINT      3)
(def ^:private SLOT-BOX-HOVER     4)

(def ^:private SLOT-IMG-SRC  0)
(def ^:private SLOT-IMG-BAKED-RL 2)  ;; backend slot: resolved ResourceLocation
(def ^:private SLOT-IMG-ALPHA 0)
(def ^:private SLOT-IMG-U     1)
(def ^:private SLOT-IMG-V     2)
(def ^:private SLOT-IMG-TEX-W 3)
(def ^:private SLOT-IMG-TEX-H 4)

(def ^:private SLOT-TEXT-TEXT    0)
(def ^:private SLOT-TEXT-BAKED  8)  ;; backend slot: baked text runs

(def ^:private SLOT-PROG-PROGRESS 0)
(def ^:private SLOT-PROG-BANDS   8)  ;; backend: baked gradient int array

(def ^:private SLOT-LINE-X1 0)
(def ^:private SLOT-LINE-Y1 1)
(def ^:private SLOT-LINE-X2 2)
(def ^:private SLOT-LINE-Y2 3)
(def ^:private SLOT-LINE-THICK 4)
(def ^:private SLOT-LINE-ALPHA 5)
(def ^:private SLOT-SHADER-PROPS 0)
(def ^:private SLOT-SHADER-PROGRESS 0)

(deftype StaticShaderSupplier [^ShaderInstance shader]
  java.util.function.Supplier
  (get [_] shader))

(defn- resolve-tex-loc [tex-key]
  (cond
    (instance? ResourceLocation tex-key) tex-key
    (keyword? tex-key) (tex-registry/resolve-texture tex-key)
    (string? tex-key) (ResourceLocation/tryParse tex-key)
    :else nil))

;; Pre-allocated buffers (zero-alloc render)
(defonce ^:private quad-xs (double-array 4))
(defonce ^:private quad-ys (double-array 4))
(defonce ^:private shared-quat (Quaternionf.))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- node-abs-x [^INode n] (.getAbsX n))
(defn- node-abs-y [^INode n] (.getAbsY n))
(defn- node-scale [^INode n] (.getCumScale n))

(defn- scaled-w [^INode n] (* (.getW n) (node-scale n)))
(defn- scaled-h [^INode n] (* (.getH n) (node-scale n)))

(defn- argb [^long a ^long r ^long g ^long b]
  (unchecked-int (bit-or (bit-shift-left a 24) (bit-shift-left r 16) (bit-shift-left g 8) b)))

;; ============================================================================
;; :box
;; ============================================================================

(defn render-box! [^GuiGraphics gg ^INode node]
  (let [x  (node-abs-x node)  y  (node-abs-y node)
        w  (scaled-w node)    h  (scaled-h node)
        ix (unchecked-int x)  iy (unchecked-int y)
        iw (unchecked-int (+ x w))  ih (unchecked-int (+ y h))]
    ;; Fill. Go via long, not a direct double->int cast: fills are stored as
    ;; doubles and Java's double->int SATURATES at Integer.MAX_VALUE, so any
    ;; ARGB with alpha >= 0x80 (value > 2^31) would clamp to 0x7FFFFFFF (faint
    ;; white). double->long->int wraps correctly and preserves the ARGB bits.
    (let [fill-argb (unchecked-int (long (.getDSlot node SLOT-BOX-FILL)))]
      (when (not= fill-argb 0)
        (.fill gg ix iy iw ih fill-argb)))
    ;; Outline (same double->long->int wrap as fill above)
    (let [outline-argb (unchecked-int (long (.getDSlot node SLOT-BOX-OUTLINE)))
          outline-w    (.getDSlot node SLOT-BOX-OUTLINE-W)]
      (when (and (not= outline-argb 0) (> outline-w 0.0))
        (.fill gg ix iy iw (unchecked-int (+ iy outline-w)) outline-argb)
        (.fill gg ix (unchecked-int (- ih outline-w)) iw ih outline-argb)
        (.fill gg ix iy (unchecked-int (+ ix outline-w)) ih outline-argb)
        (.fill gg (unchecked-int (- iw outline-w)) iy iw ih outline-argb)))
    ;; Tint overlay (hover uses SLOT-BOX-HOVER when FLAG-HOVERED set)
    (let [hovered? (.hasFlag node node/FLAG-HOVERED)
          tint (if hovered?
                 (let [ht (.getDSlot node SLOT-BOX-HOVER)]
                   (if (> ht 0.0) ht (.getDSlot node SLOT-BOX-TINT)))
                 (.getDSlot node SLOT-BOX-TINT))]
      (when (> tint 0.0)
        (let [alpha (unchecked-int (* 255.0 tint))]
          (.fill gg ix iy iw ih (argb alpha 255 255 255)))))))

(defn bake-box! [^INode _node] nil)

;; ============================================================================
;; :image
;; ============================================================================

(defn- resolve-rl
  "Parse a GUI-texture path string into a ResourceLocation. MC's SimpleTexture
   uses the RL verbatim as the resource file path, so it must include the
   '.png' extension. GUI textures are always .png; tolerate paths written
   without it (e.g. \"my_mod:textures/guis/blend_quad\") by appending it. Paths
   that already end in .png (image :src values) are left unchanged."
  ^ResourceLocation [src]
  (when (and src (string? src) (not (clojure.string/blank? src)))
    (let [src (if (clojure.string/ends-with? src ".png") src (str src ".png"))]
      (ResourceLocation/tryParse src))))

(defn bake-image! [^INode node]
  (let [src (.getOSlot node SLOT-IMG-SRC)]
    (when (string? src)
      (.setOSlot node SLOT-IMG-BAKED-RL (resolve-rl src)))))

(defn- image-tint-rgba [^INode node]
  (let [raw (.getOSlot node 1)]
    (if (vector? raw)
      (let [[r g b a] raw]
        [(float (/ (double (or r 255.0)) 255.0))
         (float (/ (double (or g 255.0)) 255.0))
         (float (/ (double (or b 255.0)) 255.0))
         (float (/ (double (or a 255.0)) 255.0))])
      [1.0 1.0 1.0 1.0])))

(defn render-image! [^GuiGraphics gg ^INode node]
  (let [rl-obj (.getOSlot node SLOT-IMG-BAKED-RL)
        ;; Fallback: if bake-image! never ran (FLAG-RENDER-DIRTY wasn't set or
        ;; the node wasn't in the tape when the flag was checked), SLOT-IMG-BAKED-RL
        ;; is nil even though SLOT-IMG-SRC has a valid string. Resolve inline so
        ;; the image draws rather than silently disappearing.
        ^ResourceLocation rl (if (instance? ResourceLocation rl-obj)
                               rl-obj
                               (when-let [src (.getOSlot node SLOT-IMG-SRC)]
                                 (when (string? src)
                                   (let [resolved (resolve-rl src)]
                                     (.setOSlot node SLOT-IMG-BAKED-RL resolved)
                                     resolved))))]
    (when rl
      (let [x  (node-abs-x node)  y  (node-abs-y node)
            w  (scaled-w node)    h  (scaled-h node)
            ix (unchecked-int x)  iy (unchecked-int y)
            iw (unchecked-int w)  ih (unchecked-int h)
            alpha (float (max 0.0 (min 1.0 (.getDSlot node SLOT-IMG-ALPHA))))
            u (.getDSlot node SLOT-IMG-U)
            v (.getDSlot node SLOT-IMG-V)
            tex-w-raw (.getDSlot node SLOT-IMG-TEX-W)
            tex-h-raw (.getDSlot node SLOT-IMG-TEX-H)
            tex-w (if (pos? tex-w-raw) tex-w-raw 1.0)
            tex-h (if (pos? tex-h-raw) tex-h-raw 1.0)
            [tr tg tb ta] (image-tint-rgba node)]
        (when (pos? alpha)
          ;; Enable blend for the alpha channel — otherwise a texture with
          ;; transparency (white glyph icons, the faint element_background) renders
          ;; opaque, filling its quad with solid RGB. :text and :nine-slice already
          ;; do this; images previously relied on whatever blend state the prior
          ;; tape node happened to leave, so alpha rendering was order-dependent.
          (RenderSystem/enableBlend)
          (RenderSystem/defaultBlendFunc)
          (RenderSystem/setShaderColor tr tg tb (* alpha ta))
          (if (and (zero? u) (zero? v) (== tex-w 1.0) (== tex-h 1.0))
            ;; Fast path: no sprite-sheet cropping requested — full-texture blit.
            (.blit gg rl ix iy iw ih 0.0 0.0 iw ih iw ih)
            ;; Cropped path: sample a [u,v]→[u+tex-w,v+tex-h] fractional UV region
            ;; (matching the old CGUI comp/render-texture-region convention), e.g.
            ;; a single frame out of a vertically-stacked sprite-sheet. Pass `gg`
            ;; so the quad is transformed by the current PoseStack (draw-tape's
            ;; leftPos/topPos translate) instead of rendering at raw coordinates.
            (GuiGraphicsHelper/blitTexturedQuad gg rl
              (float x) (float y) (float (+ x w)) (float (+ y h)) 0.0
              (float u) (float (+ u tex-w))
              (float v) (float (+ v tex-h))))
          (RenderSystem/setShaderColor 1.0 1.0 1.0 1.0))))))

;; ============================================================================
;; :text (MSDF font)
;; ============================================================================

(defn- display-text [^INode node raw]
  (let [s (str (or raw ""))]
    (if (boolean (get (.getStaticProps node) :masked?))
      (apply str (repeat (count s) "*"))
      s)))

(defn- text-font-desc [font-raw]
  (cond
    (map? font-raw) font-raw
    (keyword? font-raw) (or (cgui-font/get-font font-raw) {})
    (string? font-raw) (or (cgui-font/get-font (keyword font-raw)) {})
    :else {}))

(defn- text-align-kw [align-raw]
  (let [kw (cond
             (keyword? align-raw) align-raw
             (string? align-raw) (keyword align-raw)
             :else :left)]
    (case kw
      :center :center
      :right :right
      :left)))

(defn bake-text! [^INode node]
  ;; text kind slots (node.clj): dslots {:font-size 0 :x-offset 1 :y-offset 2}
  ;; oslots {:text 0 :color 1 :font 2 :align 3}
  (let [text (display-text node (or (.getOSlot node SLOT-TEXT-TEXT) ""))
        font-size-raw (.getDSlot node 0)
        font-size (if (pos? font-size-raw) font-size-raw 14.0)
        color-raw (.getOSlot node 1)
        color (cgui-font/normalize-color-int
               (if (number? color-raw) (long color-raw) 0xFFFFFFFF))
        font-raw (or (.getOSlot node 2) (get (.getStaticProps node) :font))
        align-raw (or (.getOSlot node 3) (get (.getStaticProps node) :align))]
    (.setOSlot node SLOT-TEXT-BAKED
                {:text text
                 :font-size (double font-size)
                 :color color
                 :font-desc (text-font-desc font-raw)
                 :align (text-align-kw align-raw)
                 :x-off (double (.getDSlot node 1))
                 :y-off (double (.getDSlot node 2))})))

(defn render-text! [^GuiGraphics gg ^INode node]
  (let [baked (.getOSlot node SLOT-TEXT-BAKED)]
    (when baked
      (let [{:keys [text color font-size font-desc align x-off y-off]} baked
            editable? (boolean (get (.getStaticProps node) :editable?))
            focused? (.hasFlag node node/FLAG-FOCUSED)
            align-kw (or align :left)
            ;; Vertical alignment: MSDF text draws with y as the em-box top, so a
            ;; node with align-h center/bottom otherwise renders top-aligned in its
            ;; box (reads as "too low" for a tall font in a short row). Offset y by
            ;; the em-box within the node's height to honor align-h (0 top / 1
            ;; middle / 2 bottom), matching how images/boxes already center.
            node-h (* (.getH node) (.getCumScale node))
            v-off (case (int (.getAlignH node))
                    1 (/ (- node-h (double font-size)) 2.0)
                    2 (- node-h (double font-size))
                    0.0)
            ;; Horizontal alignment: draw-text!'s `align` treats x as the anchor
            ;; (left→text start, center→text center, right→text end). x here is the
            ;; node's LEFT edge, so center/right must anchor at the box center/right
            ;; edge — otherwise centered/right text draws off the node box. Matches
            ;; upstream TextBox.option.align (independent of the node's align-w).
            node-w (* (.getW node) (.getCumScale node))
            h-off (case align-kw :center (/ node-w 2.0) :right node-w 0.0)
            x (+ (node-abs-x node) x-off h-off)
            y (+ (node-abs-y node) y-off v-off)]
        ;; Draw the text (masked if masked? already handled by display-text in bake-text!)
        (cgui-font/draw-text! gg (or font-desc {}) ^String text
                              x y font-size color align-kw true)
        ;; Blinking caret cursor | at the end of text (matching AcademyCraft TextBox:
        ;; font.draw("|", origin.x + sumLength(display, 0, localCaret), origin.y-1, option)
        ;; when w.isFocused() && allowEdit && GameTimer.getAbsTime() % 2 < 1).
        ;; Reactive text doesn't have a caret-position system yet, so the cursor
        ;; sits at the end of the text (caretPos == content.length). Blink alternates
        ;; each second, matching upstream's getAbsTime() % 2.
        (when (and editable? focused?
                   ;; Blink: 300ms on, 300ms off (faster than AcademyCraft's 1s cycle)
                   (< (rem (System/currentTimeMillis) 600) 300))
          (let [text-w (cgui-font/text-width (or font-desc {}) ^String text font-size)
                ;; cursor-x follows alignment (like upstream's origin.x + sumLength)
                cursor-x (case align-kw
                           :center (+ x (/ text-w 2.0))
                           :right  x
                           (+ x text-w))]
            (cgui-font/draw-text! gg (or font-desc {}) "|"
                                  cursor-x (- y 1.0) font-size color :left false)))))))

;; ============================================================================
;; :progress
;; ============================================================================

(defn bake-progress! [^INode node]
  (let [bg (.getOSlot node 1)
        fg (.getOSlot node 2)]
    (when (string? bg) (.setOSlot node 8 (resolve-rl bg)))
    (when (string? fg) (.setOSlot node 9 (resolve-rl fg)))))

(defn- icon-cutout [^INode node]
  (get (.getStaticProps node) :icon-cutout))

(defn render-progress! [^GuiGraphics gg ^INode node]
  (let [x       (node-abs-x node)   y       (node-abs-y node)
        w       (scaled-w node)     h       (scaled-h node)
        percent (double (.getDSlot node SLOT-PROG-PROGRESS))
        hint    (.getDSlot node 2)
        scroll  (.getDSlot node 3)
        filled-w (int (* percent w))
        ix (unchecked-int x) iy (unchecked-int y)
        iw (unchecked-int w) ih (unchecked-int (+ y h))
        ^ResourceLocation bg-rl (.getOSlot node 8)
        ^ResourceLocation fg-rl (.getOSlot node 9)
        cutout (icon-cutout node)
        cutout-start (when cutout (+ ix (int (:x-offset cutout 0))))
        cutout-width (when cutout (int (:w cutout 0)))
        cutout-end (when cutout (+ cutout-start cutout-width))]
    (when bg-rl
      (.blit gg bg-rl ix iy 0 0 iw ih iw ih))
    (when (and (pos? hint) (< hint percent))
      (let [hint-x (int (+ x (* hint w)))]
        (.fill gg hint-x iy (+ hint-x 1) ih (unchecked-int 0x80FF4444))))
    (letfn [(draw-segment! [seg-start seg-end]
              (when (< seg-start seg-end)
                (when fg-rl
                  (let [uoff (float (* scroll w))]
                    (.enableScissor gg seg-start iy seg-end ih)
                    (.blit gg fg-rl ix iy uoff 0.0 iw ih (float iw) (float ih))
                    (.disableScissor gg)))))]
      (when (and fg-rl (pos? filled-w))
        (let [bar-start ix
              bar-end (int (+ x filled-w))]
          (if (and cutout-start cutout-width (pos? cutout-width))
            (do (draw-segment! bar-start (min bar-end cutout-start))
                (draw-segment! (max bar-start cutout-end) bar-end))
            (draw-segment! bar-start bar-end)))))))

;; ============================================================================
;; :shader-quad / :shader-ring / :shader-progress
;; ============================================================================

(defn- render-shader-progress-node! [^GuiGraphics gg ^INode node]
  (let [props (.getOSlot node SLOT-SHADER-PROPS)
        progress (float (.getDSlot node SLOT-SHADER-PROGRESS))
        shader-id (or (:shader-id props) :ring-progbar)
        ^ResourceLocation tex-0 (resolve-tex-loc (or (:texture-0 props) (:tex-0 props)))
        ^ResourceLocation tex-1 (resolve-tex-loc (or (:texture-1 props) (:tex-1 props)))
        ^ShaderInstance si (platform-bridge/resolve-shader shader-id)]
    (when (and si tex-0 tex-1)
      (try
        (.setSampler si "TexSampler0" tex-0)
        (.setSampler si "TexSampler1" tex-1)
        (RenderSystem/setShader (StaticShaderSupplier. si))
        (when-let [u (.safeGetUniform si "Progress")] (.set u progress))
        (let [x (float (node-abs-x node)) y (float (node-abs-y node))
              x2 (float (+ (node-abs-x node) (scaled-w node)))
              y2 (float (+ (node-abs-y node) (scaled-h node)))
              ^PoseStack ps (.pose gg)
              ^PoseStack$Pose entry (.last ps)
              ^Matrix4f pose-matrix (.pose entry)
              ^Tesselator tess (Tesselator/getInstance)
              ^BufferBuilder bb (.getBuilder tess)]
          (RenderSystem/setShaderTexture (int 0) tex-0)
          (RenderSystem/setShaderTexture (int 1) tex-1)
          (.begin bb VertexFormat$Mode/QUADS DefaultVertexFormat/POSITION_TEX)
          (.vertex bb pose-matrix x y2 0.0) (.uv bb 0.0 1.0) (.endVertex bb)
          (.vertex bb pose-matrix x2 y2 0.0) (.uv bb 1.0 1.0) (.endVertex bb)
          (.vertex bb pose-matrix x2 y 0.0) (.uv bb 1.0 0.0) (.endVertex bb)
          (.vertex bb pose-matrix x y 0.0) (.uv bb 0.0 0.0) (.endVertex bb)
          (BufferUploader/drawWithShader (.end bb)))
        (RenderSystem/setShader (StaticShaderSupplier. nil))
        (catch Exception e
          (cn.li.mcmod.util.log/stacktrace "shader render failed" e))))))

(defn render-shader-quad! [^GuiGraphics gg ^INode node]
  (render-shader-progress-node! gg node))
(defn bake-shader-quad! [^INode _node] nil)
(def render-shader-ring! render-shader-quad!)
(def bake-shader-ring! bake-shader-quad!)
(defn render-shader-progress! [^GuiGraphics gg ^INode node]
  (render-shader-progress-node! gg node))
(defn bake-shader-progress! [^INode _node] nil)

;; ============================================================================
;; :gradient
;; ============================================================================

(defn render-gradient! [^GuiGraphics gg ^INode node]
  (let [x (node-abs-x node) y (node-abs-y node) w (scaled-w node) h (scaled-h node)]
    (.fill gg (unchecked-int x) (unchecked-int y)
           (unchecked-int (+ x w)) (unchecked-int (+ y h))
           (unchecked-int 0x40FFFFFF))))
(defn bake-gradient! [^INode _node] nil)

;; ============================================================================
;; :line
;; ============================================================================

(defn render-line! [^GuiGraphics gg ^INode node]
  (let [x1 (+ (node-abs-x node) (.getDSlot node SLOT-LINE-X1))
        y1 (+ (node-abs-y node) (.getDSlot node SLOT-LINE-Y1))
        x2 (+ (node-abs-x node) (.getDSlot node SLOT-LINE-X2))
        y2 (+ (node-abs-y node) (.getDSlot node SLOT-LINE-Y2))
        line-w (double (max 1.0 (.getDSlot node SLOT-LINE-THICK)))
        alpha (double (max 0.0 (min 1.0 (.getDSlot node SLOT-LINE-ALPHA))))
        color-raw (.getOSlot node 0)
        color-int (if (number? color-raw) (unchecked-int (long color-raw)) 0xFFFFFFFF)
        ^ResourceLocation tex (or (resolve-tex-loc :tex-line) (resolve-tex-loc "tex-line"))
        dx (- x2 x1) dy (- y2 y1)
        norm (Math/sqrt (+ (* dx dx) (* dy dy)))]
    (when (and tex (> norm 0.5) (> alpha 0.0))
      (try
        (let [half-w (/ line-w 2.0)
              ndx (/ (- dy) norm) ndy (/ dx norm)
              nx (* ndx half-w) ny (* ndy half-w)
              x0 (- x1 (* ndx half-w)) y0 (- y1 (* ndy half-w))
              x1e (+ x2 (* ndx half-w)) y1e (+ y2 (* ndy half-w))
              ca (float (* alpha (/ (double (bit-and (bit-shift-right color-int 24) 0xFF)) 255.0)))
              ^PoseStack ps (.pose gg)
              ^PoseStack$Pose entry (.last ps)
              ^Matrix4f pose-matrix (.pose entry)
              ^Tesselator tess (Tesselator/getInstance)
              ^BufferBuilder bb (.getBuilder tess)]
          (RenderSystem/enableBlend)
          (RenderSystem/defaultBlendFunc)
          (RenderSystem/setShaderColor 1.0 1.0 1.0 ca)
          (RenderSystem/setShaderTexture (int 0) tex)
          (.begin bb VertexFormat$Mode/QUADS DefaultVertexFormat/POSITION_TEX)
          (.vertex bb pose-matrix (float (- x0 nx)) (float (- y0 ny)) 0.0) (.uv bb 0.0 0.0) (.endVertex bb)
          (.vertex bb pose-matrix (float (+ x0 nx)) (float (+ y0 ny)) 0.0) (.uv bb 0.0 1.0) (.endVertex bb)
          (.vertex bb pose-matrix (float (+ x1e nx)) (float (+ y1e ny)) 0.0) (.uv bb 1.0 1.0) (.endVertex bb)
          (.vertex bb pose-matrix (float (- x1e nx)) (float (- y1e ny)) 0.0) (.uv bb 1.0 0.0) (.endVertex bb)
          (BufferUploader/drawWithShader (.end bb))
          (RenderSystem/setShaderColor 1.0 1.0 1.0 1.0))
        (catch Exception e
          (cn.li.mcmod.util.log/stacktrace "line render failed" e))))))
(defn bake-line! [^INode _node] nil)

;; ============================================================================
;; :group — PoseStack push/pop (handled by tape sentinels, no-op here)
;; ============================================================================

(defn render-group! [^GuiGraphics _gg ^INode _node] nil)
(defn bake-group! [^INode _node] nil)

;; ============================================================================
;; :list — parent of template items (no-op, children render individually)
;; ============================================================================

(defn render-list! [^GuiGraphics _gg ^INode _node] nil)
(defn bake-list! [^INode _node] nil)

;; ============================================================================
;; :nine-slice — 9-slice textured background (port of AcademyCraft BlendQuad)
;; ============================================================================

(def ^:private SLOT-NS-SRC 0)
(def ^:private SLOT-NS-LINE 1)
(def ^:private SLOT-NS-BAKED 2)  ;; oslot index 2 = backend slot (base 2)
(def ^:private SLOT-NS-MARGIN 0)

(defn bake-nine-slice! [^INode node]
  (let [src (.getOSlot node SLOT-NS-SRC)]
    (when (string? src)
      (.setOSlot node SLOT-NS-BAKED (resolve-rl src))))
  (let [line (.getOSlot node SLOT-NS-LINE)]
    (when (string? line)
      (.setOSlot node SLOT-NS-LINE (resolve-rl line)))))

(defn- render-nine-slice-quad!
  "Render a single textured quad via BufferBuilder (matching upstream glBegin/glEnd)."
  [^Matrix4f pose-matrix ^BufferBuilder bb
   x0 y0 x1 y1 u0 v0 u1 v1]
  (.vertex bb pose-matrix (float x0) (float y1) 0.0) (.uv bb (float u0) (float v1)) (.endVertex bb)
  (.vertex bb pose-matrix (float x1) (float y1) 0.0) (.uv bb (float u1) (float v1)) (.endVertex bb)
  (.vertex bb pose-matrix (float x1) (float y0) 0.0) (.uv bb (float u1) (float v0)) (.endVertex bb)
  (.vertex bb pose-matrix (float x0) (float y0) 0.0) (.uv bb (float u0) (float v0)) (.endVertex bb))

(defn render-nine-slice! [^GuiGraphics gg ^INode node]
  (let [^ResourceLocation tex (.getOSlot node SLOT-NS-BAKED)]
    (when tex
      (let [raw-margin (.getDSlot node SLOT-NS-MARGIN)
            margin (max 1.0 (if (pos? raw-margin) raw-margin 4.0))
            x  (node-abs-x node)  y  (node-abs-y node)
            w  (scaled-w node)    h  (scaled-h node)
            ;; 3x3 grid UVs: each tile is 1/3 of texture
            step  (/ 1.0 3.0)
            ;; Destination: x coords [x-margin, x, x+w, x+w+margin]
            d-xs [(double (- x margin)) (double x) (double (+ x w)) (double (+ x w margin))]
            d-ys [(double (- y margin)) (double y) (double (+ y h)) (double (+ y h margin))]
            ^PoseStack ps (.pose gg)
            ^PoseStack$Pose entry (.last ps)
            ^Matrix4f pose-matrix (.pose entry)
            ^Tesselator tess (Tesselator/getInstance)
            ^BufferBuilder bb (.getBuilder tess)]
        (RenderSystem/enableBlend)
        (RenderSystem/defaultBlendFunc)
        ;; Bind the position_tex shader explicitly: BufferUploader/drawWithShader
        ;; uses the *currently set* shader, and text/other nodes rendered earlier
        ;; leave a different one active — without this the quad draws untextured
        ;; (pure white). Also reset the shader color so we're not tinted.
        (RenderSystem/setShader (StaticShaderSupplier. (GameRenderer/getPositionTexShader)))
        ;; blend_quad tint: AcademyCraft draws it with Colors.monoBlend(0, 0.5) =
        ;; black @ 0.5 alpha (a translucent dark panel). White would show the raw
        ;; (light) texture — that's the "pure white" background.
        (RenderSystem/setShaderColor 0.0 0.0 0.0 0.5)
        (RenderSystem/setShaderTexture 0 tex)
        (.begin bb VertexFormat$Mode/QUADS DefaultVertexFormat/POSITION_TEX)
        (dotimes [i 3]
          (dotimes [j 3]
            (let [u0 (* i step)  u1 (+ u0 step)
                  v0 (* j step)  v1 (+ v0 step)]
              (render-nine-slice-quad! pose-matrix bb
                (d-xs i) (d-ys j) (d-xs (inc i)) (d-ys (inc j))
                u0 v0 u1 v1))))
        (BufferUploader/drawWithShader (.end bb))
        ;; Top & bottom decorative lines (matching upstream lineTex rendering)
        (when-let [^ResourceLocation line-tex (.getOSlot node SLOT-NS-LINE)]
          (let [lm 3.2
                lt -8.6  lh 12.0
                lb (- h 2.0)  lbh 8.0]
            ;; lines drawn at full white (upstream resets glColor4d(1,1,1,1))
            (RenderSystem/setShaderColor 1.0 1.0 1.0 1.0)
            (RenderSystem/setShaderTexture 0 line-tex)
            (.begin bb VertexFormat$Mode/QUADS DefaultVertexFormat/POSITION_TEX)
            ;; Top line
            (render-nine-slice-quad! pose-matrix bb
              (- x lm) (+ y lt) (+ x w lm) (+ y lt lh) 0.0 0.0 1.0 1.0)
            ;; Bottom line
            (render-nine-slice-quad! pose-matrix bb
              (- x lm) (+ y lb) (+ x w lm) (+ y lb lbh) 0.0 0.0 1.0 1.0)
            (BufferUploader/drawWithShader (.end bb))))
        (RenderSystem/setShaderColor 1.0 1.0 1.0 1.0)))))

;; ============================================================================
;; :glow-line — matches upstream ACRenderingHelper.drawGlow + lineSegment
;; ============================================================================

(def ^:private SLOT-GL-X0 0) (def ^:private SLOT-GL-X1 1)
(def ^:private SLOT-GL-Y 2) (def ^:private SLOT-GL-LINEW 3)
(def ^:private SLOT-GL-GLOWSZ 4)

(defn- bake-glow-line! [^INode node]
  (when (nil? (.getOSlot node 0))
    (.setOSlot node 0
                {:lu (resolve-rl "my_mod:textures/guis/glow_lu")
                 :ru (resolve-rl "my_mod:textures/guis/glow_ru")
                 :ld (resolve-rl "my_mod:textures/guis/glow_ld")
                 :rd (resolve-rl "my_mod:textures/guis/glow_rd")
                 :l  (resolve-rl "my_mod:textures/guis/glow_left")
                 :r  (resolve-rl "my_mod:textures/guis/glow_right")
                 :u  (resolve-rl "my_mod:textures/guis/glow_up")
                 :d  (resolve-rl "my_mod:textures/guis/glow_down")})))

(defn- glow-quad! [^Matrix4f pm ^BufferBuilder bb x0 y0 x1 y1 u0 v0 u1 v1]
  (.vertex bb pm (float x0) (float y1) 0.0) (.uv bb (float u0) (float v1)) (.endVertex bb)
  (.vertex bb pm (float x1) (float y1) 0.0) (.uv bb (float u1) (float v1)) (.endVertex bb)
  (.vertex bb pm (float x1) (float y0) 0.0) (.uv bb (float u1) (float v0)) (.endVertex bb)
  (.vertex bb pm (float x0) (float y0) 0.0) (.uv bb (float u0) (float v0)) (.endVertex bb))

(defn render-glow-line! [^GuiGraphics gg ^INode node]
  (when (.isVisible node)
    (let [x0 (node-abs-x node)  y0 (node-abs-y node)
          gx0 (+ x0 (.getDSlot node SLOT-GL-X0))
          gx1 (+ x0 (.getDSlot node SLOT-GL-X1))
          gy  (+ y0 (.getDSlot node SLOT-GL-Y))
          line-w (max 1.0 (.getDSlot node SLOT-GL-LINEW))
          glow-sz (max 1.0 (.getDSlot node SLOT-GL-GLOWSZ))
          hw (/ line-w 2.0)
          texs (.getOSlot node 0)]
      (when texs
        (let [s (double glow-sz)
              glx0 (- gx0 s) glx1 (+ gx1 s)
              gly0 (- gy s)  gly1 (+ gy s)
              gy0 (- gy hw)  gy1 (+ gy hw)
              ^PoseStack ps (.pose gg)
              ^Matrix4f pm (.pose (.last ps))
              ^Tesselator tess (Tesselator/getInstance)
              ^BufferBuilder bb (.getBuilder tess)]
          (RenderSystem/enableBlend)
          (RenderSystem/defaultBlendFunc)
          (.begin bb VertexFormat$Mode/QUADS DefaultVertexFormat/POSITION_TEX)
          ;; corners
          (RenderSystem/setShaderTexture 0 ^ResourceLocation (:lu texs))
          (glow-quad! pm bb glx0 gly0 gx0 gy0 0.0 0.0 1.0 1.0)
          (RenderSystem/setShaderTexture 0 ^ResourceLocation (:ru texs))
          (glow-quad! pm bb gx1 gly0 glx1 gy0 0.0 0.0 1.0 1.0)
          (RenderSystem/setShaderTexture 0 ^ResourceLocation (:ld texs))
          (glow-quad! pm bb glx0 gy1 gx0 gly1 0.0 0.0 1.0 1.0)
          (RenderSystem/setShaderTexture 0 ^ResourceLocation (:rd texs))
          (glow-quad! pm bb gx1 gy1 glx1 gly1 0.0 0.0 1.0 1.0)
          ;; edges
          (RenderSystem/setShaderTexture 0 ^ResourceLocation (:l texs))
          (glow-quad! pm bb glx0 gy0 gx0 gy1 0.0 0.0 1.0 1.0)
          (RenderSystem/setShaderTexture 0 ^ResourceLocation (:r texs))
          (glow-quad! pm bb gx1 gy0 glx1 gy1 0.0 0.0 1.0 1.0)
          (RenderSystem/setShaderTexture 0 ^ResourceLocation (:u texs))
          (glow-quad! pm bb gx0 gly0 gx1 gy0 0.0 0.0 1.0 1.0)
          (RenderSystem/setShaderTexture 0 ^ResourceLocation (:d texs))
          (glow-quad! pm bb gx0 gy1 gx1 gly1 0.0 0.0 1.0 1.0)
          (BufferUploader/drawWithShader (.end bb))
          ;; center bright line
          (RenderSystem/setShaderTexture 0 (resolve-rl "my_mod:textures/guis/line"))
          (.begin bb VertexFormat$Mode/QUADS DefaultVertexFormat/POSITION_TEX)
          (glow-quad! pm bb gx0 (- gy hw) gx1 (+ gy hw) 0.0 0.0 1.0 1.0)
          (BufferUploader/drawWithShader (.end bb)))))))

;; ============================================================================
;; :crosshair
;; ============================================================================

;; ============================================================================
;; :crosshair
;; ============================================================================

(def ^:private crosshair-ring-unit-vecs
  "Precomputed [cos sin] for the 24-point ring — the reflection crosshair only
  scales this fixed unit circle by `radius` each frame; no need to recompute
  Math/cos + Math/sin 24x every frame it's active."
  (mapv (fn [idx]
          (let [a (/ (* 2.0 Math/PI idx) 24.0)]
            [(Math/cos a) (Math/sin a)]))
        (range 24)))

(defn render-crosshair! [^GuiGraphics gg ^INode node]
  (let [cx (unchecked-int (node-abs-x node))
        cy (unchecked-int (node-abs-y node))
        p (double (.getDSlot node 0))
        amp (double (.getDSlot node 1))
        pulse (+ 1.0 (* 0.5 (Math/sin (* 2.0 Math/PI p))))
        radius (+ 11.0 (* 4.0 pulse amp))
        gap (+ 6 (int (* 2.0 pulse amp)))
        len (+ 8 (int (* 2.0 pulse amp)))
        line-color 0xB4E8F8FF
        ring-color 0x88DDF2FF]
    (.fill gg (- cx len) (dec cy) (- cx gap) (inc cy) line-color)
    (.fill gg (+ cx gap) (dec cy) (+ cx len) (inc cy) line-color)
    (.fill gg (dec cx) (- cy len) (inc cx) (- cy gap) line-color)
    (.fill gg (dec cx) (+ cy gap) (inc cx) (+ cy len) line-color)
    (doseq [[cos-a sin-a] crosshair-ring-unit-vecs]
      (let [rx (+ cx (int (Math/round (* radius cos-a))))
            ry (+ cy (int (Math/round (* radius sin-a))))]
        (.fill gg (dec rx) (dec ry) (inc rx) (inc ry) ring-color)))))

(defn bake-crosshair! [^INode _node] nil)

(defn render-embedded-runtime!
  "Render a UiRt embedded at screen offset (CGUI widget host)."
  [^GuiGraphics gg ^UiRt rt left top w h partial-ticks]
  (when (and rt (not (cn.li.mcmod.ui.runtime/disposed? rt)))
    (clock/tick! rt partial-ticks)
    (cn.li.mcmod.ui.runtime/resize! rt (double w) (double h))
    (cn.li.mcmod.ui.runtime/flush! rt)
    (ui-layout/ensure-layout! rt)
    (ui-layout/ensure-tape! rt)
    (draw-tape! gg rt (int left) (int top))))

;; ============================================================================
;; Kind fn-map (installed via install-adapter! into [:platform :ui-kinds])
;; ============================================================================

(def kind-renderers
  {:box             {:render! render-box!             :bake! bake-box!}
   :image           {:render! render-image!           :bake! bake-image!}
   :text            {:render! render-text!            :bake! bake-text!}
   :progress        {:render! render-progress!        :bake! bake-progress!}
   :shader-quad     {:render! render-shader-quad!     :bake! bake-shader-quad!}
   :shader-ring     {:render! render-shader-ring!     :bake! bake-shader-ring!}
   :shader-progress {:render! render-shader-progress! :bake! bake-shader-progress!}
   :gradient        {:render! render-gradient!        :bake! bake-gradient!}
   :line            {:render! render-line!            :bake! bake-line!}
   :group           {:render! render-group!           :bake! bake-group!}
   :list            {:render! render-list!            :bake! bake-list!}
   :nine-slice      {:render! render-nine-slice!      :bake! bake-nine-slice!}
   :glow-line       {:render! render-glow-line!       :bake! bake-glow-line!}
   :crosshair       {:render! render-crosshair!       :bake! bake-crosshair!}})

;; ============================================================================
;; draw-tape! — flat tape render loop
;; ============================================================================

(defn draw-tape!
  "Render the flat tape: iterate Object[].
   Node with RENDER_DIRTY → run :bake! first (resolve textures / bake text /
   pre-compute gradient bands into backend slots), clear the flag, then :render!
   reads only cached fields. Sentinels → PoseStack push/pop.

   Node abs coords are 0-rooted; `left`/`top` are the GUI's on-screen origin
   (container leftPos/topPos, standalone screen offset, or an embedded widget's
   offset). We translate the PoseStack by (left,top) so the tree lands where the
   input layer already assumes it is (input/* subtracts left/top before
   hit-testing) and where vanilla renders the container slots (leftPos+slot.x)."
  [^GuiGraphics gg ^UiRt rt left top]
  (let [^objects tape (cn.li.mcmod.ui.runtime/get-tape-arr rt)
        n (alength tape)
        push-clip ui-layout/push-clip-sentinel
        pop-clip  ui-layout/pop-clip-sentinel
        push-xf   ui-layout/push-transform-sentinel
        pop-xf    ui-layout/pop-transform-sentinel]
    (when (pos? n)
      (let [^PoseStack pose (.pose gg)]
        (.pushPose pose)
        (.translate pose (double left) (double top) 0.0)
        (loop [i 0]
          (when (< i n)
            (let [entry (aget tape i)]
              (if (instance? INode entry)
                (let [^INode nd entry
                      kdef (get kind-renderers (.getKind nd))]
                  ;; Bake on dirty: only recompute caches when a binding wrote the node
                  (when (.hasFlag nd node/FLAG-RENDER-DIRTY)
                    (when-let [bake-fn (:bake! kdef)]
                      (bake-fn nd))
                    (.clearFlag nd node/FLAG-RENDER-DIRTY))
                  (when-let [render-fn (:render! kdef)]
                    (render-fn gg nd)))
                ;; Sentinel dispatch
                (cond (identical? push-clip entry) (.pushPose pose)
                      (identical? pop-clip entry)  (.popPose pose)
                      (identical? push-xf entry)   (.pushPose pose)
                      (identical? pop-xf entry)    (.popPose pose))))
            (recur (unchecked-inc-int i))))
        (.popPose pose)))))

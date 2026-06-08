(ns cn.li.mc1201.gui.cgui.font
  "CLIENT-ONLY: Custom TrueType font rendering system.

  Ported from LambdaLib2's cn.lambdalib2.render.font package.

  Architecture:
  - Font records wrap a java.awt.Font with a glyph texture atlas
  - Glyphs are rendered on-demand to a 2048×2048 RGBA atlas (raw GL texture)
  - Text rendering uses Minecraft 1.20's BufferBuilder + PoseStack

  Font registry maps keyword names → font records.

  Usage:
    (require '[cn.li.mc1201.gui.cgui.font :as cfont])
    (cfont/register-font! :ac-normal (cfont/load-system-font \"Microsoft YaHei\" Font/PLAIN 24))
    (cfont/text-width (cfont/get-font :ac-normal) \"Hello\" 12.0)
    (cfont/draw-text! gg (cfont/get-font :ac-normal) \"Hello\" x y 12.0 color :left)"
  (:import [java.awt Font GraphicsEnvironment RenderingHints Color]
           [java.awt.image BufferedImage DataBuffer DataBufferInt]
           [java.nio ByteBuffer ByteOrder]
           [java.util BitSet]
           [com.mojang.blaze3d.systems RenderSystem]
           [com.mojang.blaze3d.vertex BufferBuilder BufferUploader DefaultVertexFormat
            Tesselator VertexFormat$Mode PoseStack]
           [net.minecraft.client.gui GuiGraphics]
           [org.lwjgl.opengl GL11 GL14 GL30]))

;; ============================================================================
;; Font Registry
;; ============================================================================

(defonce ^:private registry (atom {}))

(defn register-font!
  "Register a font record under `name` (keyword)."
  [name font-record]
  (swap! registry assoc name font-record)
  name)

(defn get-font
  "Look up a registered font by name. Returns nil if not found."
  [name]
  (get @registry name))

(defn font-exists?
  "Check if a font name is registered."
  [name]
  (contains? @registry name))

;; ============================================================================
;; Font Loading
;; ============================================================================

(defn load-system-font
  "Load a system TrueType font by name, creating a font record.

  Args:
  - font-names: String or seq of font names to try (in order)
  - style: Font/PLAIN, Font/BOLD, or Font/ITALIC
  - size: Base point size for the java.awt.Font (e.g., 24)

  Returns a font record map:
    :awt-font   — java.awt.Font
    :char-size  — glyph cell pixel size (= size * 1.4)
    :atlas      — atom {:textures [tex-ids...] :lookup {code-point->glyph-map} :step N :max-step N}
    :name       — name of the font loaded"
  [font-names style size]
  (let [names (if (string? font-names) [font-names] (vec font-names))
        all-system-fonts (vec (.getAllFonts (GraphicsEnvironment/getLocalGraphicsEnvironment)))
        matching (some (fn [candidate]
                         (some #(when (= (.toLowerCase (.getName ^Font %))
                                        (.toLowerCase candidate))
                                  %)
                               all-system-fonts))
                       names)
        awt-font (if matching
                   (.deriveFont ^Font matching (float size))
                   (java.awt.Font. (first names) style size))]
    {:awt-font awt-font
     :char-size (int (* size 1.4))
     :atlas (atom {:textures [] :lookup {} :step 0 :dirty (BitSet.)})
     :name (or (first names) "default")}))

;; ============================================================================
;; Glyph Atlas
;; ============================================================================

(def ^:private ^:const atlas-size 2048)

(defn- new-atlas-texture!
  "Create a new empty RGBA texture for the glyph atlas."
  []
  (let [tex-id (GL11/glGenTextures)
        limit (min 2048 (int (GL11/glGetInteger GL11/GL_MAX_TEXTURE_SIZE)))]
    (GL11/glBindTexture GL11/GL_TEXTURE_2D tex-id)
    (GL11/glTexImage2D GL11/GL_TEXTURE_2D 0 GL11/GL_RGBA
                       limit limit 0
                       GL11/GL_RGBA GL11/GL_FLOAT
                       (ByteBuffer/allocateDirect 0))
    (GL11/glTexParameteri GL11/GL_TEXTURE_2D GL11/GL_TEXTURE_MIN_FILTER GL14/GL_LINEAR_MIPMAP_LINEAR)
    (GL11/glTexParameteri GL11/GL_TEXTURE_2D GL11/GL_TEXTURE_MAG_FILTER GL11/GL_LINEAR)
    (GL11/glTexParameteri GL11/GL_TEXTURE_2D GL11/GL_TEXTURE_WRAP_S GL11/GL_CLAMP)
    (GL11/glTexParameteri GL11/GL_TEXTURE_2D GL11/GL_TEXTURE_WRAP_T GL11/GL_CLAMP)
    (GL11/glTexParameterf GL11/GL_TEXTURE_2D GL14/GL_TEXTURE_LOD_BIAS (float -0.65))
    (GL11/glTexEnvi GL11/GL_TEXTURE_ENV GL11/GL_TEXTURE_ENV_MODE GL11/GL_MODULATE)
    (GL11/glBindTexture GL11/GL_TEXTURE_2D 0)
    tex-id))

(defn- ensure-glyph!
  "Ensure a code point has a glyph rendered in the atlas."
  [font-record code-point]
  (let [atlas-atom (:atlas font-record)
        char-size (:char-size font-record)
        awt-font (:awt-font font-record)
        limit (min 2048 (int (GL11/glGetInteger GL11/GL_MAX_TEXTURE_SIZE)))
        max-per-col (int (Math/floor (/ (double limit) (double char-size))))
        max-step (* max-per-col max-per-col)]
    (when-not (get (:lookup @atlas-atom) code-point)
      (let [step (:step @atlas-atom)]
        (when (or (zero? (count (:textures @atlas-atom)))
                  (>= step max-step))
          (let [new-tex (new-atlas-texture!)]
            (swap! atlas-atom (fn [a]
                                (-> a
                                    (update :textures conj new-tex)
                                    (assoc :step 0))))))
        (let [image (BufferedImage. char-size char-size BufferedImage/TYPE_INT_ARGB)
              ^java.awt.Graphics2D gfx (.createGraphics image)
              _ (doto gfx
                  (.setFont awt-font)
                  (.setRenderingHint RenderingHints/KEY_ANTIALIASING
                                     RenderingHints/VALUE_ANTIALIAS_ON)
                  (.setBackground (Color. 255 255 255 0))
                  (.clearRect 0 0 char-size char-size)
                  (.setColor Color/WHITE))
              fm (.getFontMetrics gfx awt-font)
              width (.charWidth fm (int code-point))]
          (.drawString gfx (String/valueOf (Character/toChars code-point)) 3
                       (+ 1 (.getAscent fm)))
          (.dispose gfx)
          (let [^DataBufferInt db (.getDataBuffer (.getData image))
                raw-data (.getData db)
                byte-count (* char-size char-size 4)
                bytes (byte-array byte-count)]
            (dotimes [i (alength raw-data)]
              (let [val (aget raw-data i)
                    new-idx (* i 4)
                    r (bit-and (bit-shift-right val 16) 0xFF)
                    g (bit-and (bit-shift-right val 8) 0xFF)
                    b (bit-and val 0xFF)
                    l (int (/ (+ r g b) 3))
                    alpha (int (/ (* (bit-and (bit-shift-right val 24) 0xFF) l) 255))]
                (aset-byte bytes new-idx (byte -1))
                (aset-byte bytes (inc new-idx) (byte -1))
                (aset-byte bytes (+ new-idx 2) (byte -1))
                (aset-byte bytes (+ new-idx 3) (byte alpha))))
            (let [bb (-> (ByteBuffer/allocateDirect byte-count)
                         (.order (ByteOrder/nativeOrder))
                         (.put bytes))
                  _ (.flip bb)
                  raster-x (* (mod step max-per-col) char-size)
                  raster-y (* (int (/ step max-per-col)) char-size)
                  tex-id (last (:textures @atlas-atom))]
              (GL11/glBindTexture GL11/GL_TEXTURE_2D tex-id)
              (GL11/glTexSubImage2D GL11/GL_TEXTURE_2D 0
                                    (int raster-x) (int raster-y)
                                    (int char-size) (int char-size)
                                    GL11/GL_RGBA GL11/GL_UNSIGNED_BYTE bb)
              (GL11/glBindTexture GL11/GL_TEXTURE_2D 0)
              (let [tex-index (dec (count (:textures @atlas-atom)))
                    u (/ (double raster-x) (double limit))
                    v (/ (double raster-y) (double limit))
                    step' (inc step)]
                (swap! atlas-atom
                       (fn [a]
                         (-> a
                             (assoc :step step')
                             (assoc-in [:lookup code-point]
                                       {:code-point code-point
                                        :width width
                                        :tex-index tex-index
                                        :u u
                                        :v v})
                             (update :dirty #(doto % (.set tex-index))))))))))))))

;; ============================================================================
;; Text Measurement
;; ============================================================================

(defn text-width
  "Calculate the width of `text` when rendered with `font-record`
  at the given `font-size` (in points)."
  [font-record ^String text font-size]
  (let [char-size (:char-size font-record)]
    (loop [i 0 sum 0.0]
      (if (< i (.length text))
        (let [cp (.codePointAt text i)]
          (ensure-glyph! font-record cp)
          (let [glyph (get (:lookup @(:atlas font-record)) cp)
                glyph-w (if glyph (:width glyph) char-size)]
            (recur (+ i (Character/charCount cp))
                   (+ sum glyph-w))))
        (* sum (/ (double font-size) (double char-size)))))))

;; ============================================================================
;; Text Rendering
;; ============================================================================

(defn- simple-blit!
  "Blit a texture region using BufferBuilder."
  [^PoseStack ps x y draw-w draw-h texture-id u v tex-step r g b a]
  (RenderSystem/setShaderTexture 0 texture-id)
  (RenderSystem/setShaderColor (float r) (float g) (float b) (float a))
  (RenderSystem/enableBlend)
  (let [^BufferBuilder builder (.begin (Tesselator/getInstance) VertexFormat$Mode/QUADS
                                       DefaultVertexFormat/POSITION_TEX)
        mat (.last (.pose ps))]
    (.vertex builder mat (float x)        (float (+ y draw-h)) 0.0)
    (.uv builder (float u) (float (+ v tex-step)))
    (.endVertex builder)
    (.vertex builder mat (float (+ x draw-w)) (float (+ y draw-h)) 0.0)
    (.uv builder (float (+ u tex-step)) (float (+ v tex-step)))
    (.endVertex builder)
    (.vertex builder mat (float (+ x draw-w)) (float y)        0.0)
    (.uv builder (float (+ u tex-step)) (float v))
    (.endVertex builder)
    (.vertex builder mat (float x)        (float y)        0.0)
    (.uv builder (float u) (float v))
    (.endVertex builder)
    (BufferUploader/drawWithShader (.end builder))))

(defn- flush-dirty-atlas-textures!
  "Generate mipmaps for any dirty atlas textures."
  [font-record]
  (let [atlas-atom (:atlas font-record)
        dirty ^BitSet (:dirty @atlas-atom)
        textures (:textures @atlas-atom)]
    (dotimes [i (.length dirty)]
      (when (.get dirty i)
        (let [tex-id (nth textures i nil)]
          (when tex-id
            (GL11/glBindTexture GL11/GL_TEXTURE_2D tex-id)
            (GL30/glGenerateMipmap GL11/GL_TEXTURE_2D)
            (GL11/glBindTexture GL11/GL_TEXTURE_2D 0)))))
    (.clear dirty)))

(defn draw-text!
  "Draw `text` using a custom font record."
  [^GuiGraphics gg font-record ^String text x y font-size color align]
  (when (and font-record (seq text))
    (let [char-size (:char-size font-record)
          scale (/ (double font-size) (double char-size))
          ^PoseStack ps (.pose gg)
          atlas-atom (:atlas font-record)
          atlas-limit (min 2048 (int (GL11/glGetInteger GL11/GL_MAX_TEXTURE_SIZE)))
          tex-step (/ 1.0 (int (/ atlas-limit char-size)))
          total-w (text-width font-record text font-size)
          x' (case align
               :center (- x (/ total-w 2.0))
               :right  (- x total-w)
               x)
          r (/ (double (bit-and (bit-shift-right color 16) 0xFF)) 255.0)
          g (/ (double (bit-and (bit-shift-right color 8) 0xFF)) 255.0)
          b (/ (double (bit-and color 0xFF)) 255.0)
          a (if (pos? (bit-and color 0xFF000000))
              (/ (double (bit-and (bit-shift-right color 24) 0xFF)) 255.0)
              1.0)]
      (flush-dirty-atlas-textures! font-record)
      (let [prev-tex (GL11/glGetInteger GL11/GL_TEXTURE_BINDING_2D)]
        (try
          (loop [i 0 cur-x (double x')]
            (when (< i (.length text))
              (let [cp (.codePointAt text i)
                    char-count (Character/charCount cp)
                    _ (ensure-glyph! font-record cp)
                    glyph (get (:lookup @atlas-atom) cp)
                    tex-id (when glyph (nth (:textures @atlas-atom) (:tex-index glyph) nil))
                    glyph-w (if glyph (:width glyph) char-size)
                    draw-w (* glyph-w scale)]
                (when glyph
                  (simple-blit! ps cur-x y draw-w font-size
                                tex-id (:u glyph) (:v glyph) tex-step
                                r g b a))
                (recur (+ i char-count) (+ cur-x draw-w)))))
          (finally
            (GL11/glBindTexture GL11/GL_TEXTURE_2D prev-tex)))))))

;; ============================================================================
;; Default Font (Minecraft built-in)
;; ============================================================================

(def default-mc-font
  "Marker value for Minecraft's default font."
  nil)

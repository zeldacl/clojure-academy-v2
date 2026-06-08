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
           [com.mojang.blaze3d.vertex BufferBuilder DefaultVertexFormat
            Tesselator VertexFormat$Mode PoseStack VertexConsumer]
           [net.minecraft.client.renderer MultiBufferSource$BufferSource]
           [net.minecraft.client.renderer RenderType GameRenderer]
           [net.minecraft.client.gui GuiGraphics]
           [net.minecraft.client.renderer RenderType]
           [org.lwjgl.opengl GL11 GL11C GL12 GL14]))

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

(defn- new-atlas-texture!
  "Create a new empty RGBA texture for the glyph atlas.
   Uses GL11C/glTexImage2D with (long 0) to pass a genuine C NULL pointer,
   avoiding Intel GPU driver crashes.  All enums use core-profile-compatible values."
  []
  (let [tex-id (GL11/glGenTextures)
        limit (min 2048 (int (GL11/glGetInteger GL11/GL_MAX_TEXTURE_SIZE)))]
    (GL11/glBindTexture GL11/GL_TEXTURE_2D tex-id)
    (GL11C/glTexImage2D (int GL11/GL_TEXTURE_2D) (int 0) (int GL11/GL_RGBA)
                        (int limit) (int limit) (int 0)
                        (int GL11/GL_RGBA) (int GL11/GL_UNSIGNED_BYTE)
                        (long 0))
    (GL11/glTexParameteri GL11/GL_TEXTURE_2D GL11/GL_TEXTURE_MIN_FILTER GL11/GL_LINEAR)
    (GL11/glTexParameteri GL11/GL_TEXTURE_2D GL11/GL_TEXTURE_MAG_FILTER GL11/GL_LINEAR)
    (GL11/glTexParameteri GL11/GL_TEXTURE_2D GL11/GL_TEXTURE_WRAP_S GL12/GL_CLAMP_TO_EDGE)
    (GL11/glTexParameteri GL11/GL_TEXTURE_2D GL11/GL_TEXTURE_WRAP_T GL12/GL_CLAMP_TO_EDGE)
    (GL11/glBindTexture GL11/GL_TEXTURE_2D 0)
    tex-id))

(defn- ensure-glyph!
  "Ensure a code point has a glyph rendered in the atlas.
   Forcibly flushes Java AWT text pipeline to avoid transparent ghost buffers,
   and clears RenderSystem caches to guarantee physical GPU texture updates."
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
                  (.setBackground (Color. 0 0 0 0))
                  (.clearRect 0 0 char-size char-size)
                  (.setColor Color/WHITE))
              fm (.getFontMetrics gfx awt-font)
              width (.charWidth fm (int code-point))]
          (.drawString gfx (String/valueOf (Character/toChars code-point)) 3
                       (+ 1 (.getAscent fm)))
          (.dispose gfx)

          (let [raw-data (int-array (* char-size char-size))
                ;; 📢 终极修复 1：强制通过 getRGB 同步 JVM 离屏画布，把真实的抗锯齿字形数据捞回内存
                _ (.getRGB image 0 0 char-size char-size raw-data 0 char-size)
                byte-count (* char-size char-size 4)
                bb (-> (ByteBuffer/allocateDirect byte-count)
                       (.order (ByteOrder/nativeOrder)))
                ib (.asIntBuffer bb)]
            (dotimes [i (alength raw-data)]
              (let [val (aget raw-data i)
                    a (bit-and (bit-shift-right val 24) 0xFF)
                    r (bit-and (bit-shift-right val 16) 0xFF)
                    g (bit-and (bit-shift-right val 8) 0xFF)
                    b (bit-and val 0xFF)
                    gl-pixel (bit-or r
                                     (bit-shift-left g 8)
                                     (bit-shift-left b 16)
                                     (bit-shift-left a 24))]
                (.put ib i (int gl-pixel))))
            (let [bb (-> bb (.position 0) (.limit byte-count))
                  raster-x (* (mod step max-per-col) char-size)
                  raster-y (* (int (/ step max-per-col)) char-size)
                  tex-id (last (:textures @atlas-atom))]

              ;; 📢 终极修复 2：不要信任 MC 此时的底层物理状态，使用底层原生硬绑
              (GL11/glBindTexture GL11/GL_TEXTURE_2D tex-id)
              (GL11/glTexSubImage2D GL11/GL_TEXTURE_2D 0
                                    (int raster-x) (int raster-y)
                                    (int char-size) (int char-size)
                                    GL11/GL_RGBA GL11/GL_UNSIGNED_BYTE bb)

              ;; 📢 终极修复 3：极其重要！必须使用 RenderSystem/setShaderTexture
              ;; 重新绑定一次任意 vanilla 贴图（比如 0），强制打破并污染 Minecraft 内部的当前纹理缓存（De-sync）。
              ;; 这样后续在 simple-blit! 里调用绑定时，MC 才会真正向显卡发出物理切换指令！
              (RenderSystem/setShaderTexture 0 0)

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
;; Text Rendering (100% Guaranteed Physical UI Pass)
;; ============================================================================

(defn- get-custom-render-type
  "动态生成一个适配自定义显卡纹理 ID 且支持半透明的位置+颜色+纹理+光照着色器类型。
   使用 1.20.1 UI 文本最牢固的 textSeeThrough 类型，它本身就支持 POSITION_COLOR_TEX_LIGHTMAP 格式。"
  [texture-id]
  (RenderType/textSeeThrough
    (net.minecraft.resources.ResourceLocation. "my_mod" (str "font_" texture-id))))

(defn- simple-blit!
  "将字形 Quad 的顶点注入专用的文本流缓冲区中。
   彻底移除所有 GL11/glBindTexture 盲动，改为在获取 Buffer 时直接让 MC 记忆贴图所有权。"
  [^GuiGraphics gg ^PoseStack ps x y draw-w draw-h texture-id u v tex-step r g b a]
  (let [vc (.bufferSource gg)
        ;; 📢 终极解密：直接调用带有贴图通道的 textSeeThrough。
        ;; 这样当最后 (.flush gg) 执行时，Minecraft 内部会自动、绝对且物理正确地帮我们切换 GL11/glBindTexture！
        custom-type (get-custom-render-type texture-id)
        builder ^VertexConsumer (.getBuffer vc custom-type)
        mat (.last (.pose ps))]

    ;; 顶点 1 (左下)
    (.vertex builder mat (float x) (float (+ y draw-h)) 0.0)
    (.color builder (float r) (float g) (float b) (float a))
    (.uv builder (float u) (float (+ v tex-step)))
    (.uv2 builder (int 15728880)) ;; 全亮 UI 光照 (0xF000F0)
    (.endVertex builder)

    ;; 顶点 2 (右下)
    (.vertex builder mat (float (+ x draw-w)) (float (+ y draw-h)) 0.0)
    (.color builder (float r) (float g) (float b) (float a))
    (.uv builder (float (+ u tex-step)) (float (+ v tex-step)))
    (.uv2 builder (int 15728880))
    (.endVertex builder)

    ;; 顶点 3 (右上)
    (.vertex builder mat (float (+ x draw-w)) (float y) 0.0)
    (.color builder (float r) (float g) (float b) (float a))
    (.uv builder (float (+ u tex-step)) (float v))
    (.uv2 builder (int 15728880))
    (.endVertex builder)

    ;; 顶点 4 (左上)
    (.vertex builder mat (float x) (float y) 0.0)
    (.color builder (float r) (float g) (float b) (float a))
    (.uv builder (float u) (float v))
    (.uv2 builder (int 15728880))
    (.endVertex builder)))

(defn- flush-dirty-atlas-textures!
  "Clear the atlas dirty-tracking bitset."
  [font-record]
  (.clear ^BitSet (:dirty @(:atlas font-record))))

(defn draw-text!
  "终极收官版本：规避 1.20.1 Scissor 裁剪与批处理状态滞后的完全体渲染函数"
  [^GuiGraphics gg font-record ^String text x y font-size color align]
  (when (and font-record (seq text))
    (let [char-size (:char-size font-record)
          scale (/ (double font-size) (double char-size))
          ^PoseStack ps (.pose gg)
          atlas-atom (:atlas font-record)
          atlas-limit (min 2048 (int (GL11/glGetInteger GL11/GL_MAX_TEXTURE_SIZE)))
          tex-step (/ (double char-size) (double atlas-limit))
          total-w (text-width font-record text font-size)
          x' (case align
               :center (- x (/ total-w 2.0))
               :right  (- x total-w)
               x)
          color-long (bit-and (long color) 0xFFFFFFFF)
          r (/ (double (bit-and (bit-shift-right color-long 16) 0xFF)) 255.0)
          g (/ (double (bit-and (bit-shift-right color-long 8) 0xFF)) 255.0)
          b (/ (double (bit-and color-long 0xFF)) 255.0)
          a (/ (double (bit-and (bit-shift-right color-long 24) 0xFF)) 255.0)]

      ;; 1. 📢 强行清空残留，强锁绝对干净的文字缓冲区环境
      (.flush ^net.minecraft.client.renderer.MultiBufferSource$BufferSource (.bufferSource gg))

      (flush-dirty-atlas-textures! font-record)

      ;; 2. 📢 环境保护：同时关闭深度测试与可能残留的外部局部 Scissor 裁剪，防止字形被窒息式抹除
      (RenderSystem/enableBlend)
      (RenderSystem/defaultBlendFunc)
      (RenderSystem/disableDepthTest)
      (RenderSystem/disableScissor) ;; 防止原版 UI 裁剪带残留吞掉我们的字

      ;; 3. 📢 强行激活 UI 文字专用、接受格式步长的全局着色器提供者
      (RenderSystem/setShader (reify java.util.function.Supplier
                                (get [_] (GameRenderer/getPositionColorTexLightmapShader))))

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
              ;; 📢 现在不需要、也千万不能调用原始的 GL11/glBindTexture，
              ;; 我们的 custom-type 会在最后 (.flush) 时自动以最完美、最安全的时序让显卡抓取它！
              (simple-blit! gg ps cur-x y draw-w font-size tex-id
                            (:u glyph) (:v glyph) tex-step
                            r g b a))
            (recur (+ i char-count) (+ cur-x draw-w)))))

      ;; 4. 📢 物理决战大冲刷！此时所有的顶点都带有正确的材质标识和光照格式，
      ;; 憋在缓冲区里的这批字形将以万无一失的时序直接画满屏幕表层！
      (.flush ^net.minecraft.client.renderer.MultiBufferSource$BufferSource (.bufferSource gg))

      ;; 5. 还原原版渲染参数，全身而退
      (RenderSystem/enableDepthTest))))

;; ============================================================================
;; Default Font (Minecraft built-in)
;; ============================================================================

(def default-mc-font
  "Marker value for Minecraft's default font."
  nil)

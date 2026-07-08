(ns cn.li.mc1201.gui.cgui.renderer
  "CLIENT-ONLY CGUI rendering and root sizing logic.

  Font size contract: :font-size N = N screen pixels. STB bake height 8px; scale = N / 8."
  (:require [clojure.string :as str]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mc1201.gui.cgui.font :as font-api]
            [cn.li.mc1201.gui.cgui.assets :as assets]
            [cn.li.mc1201.gui.cgui.traversal :as traversal]
            [cn.li.mc1201.gui.reactive.render :as reactive-render]
            [cn.li.mcmod.gui.components :as gui-comp]
            [cn.li.mcmod.client.platform-bridge :as platform-bridge]
            [cn.li.mcmod.util.log :as log])
  (:import (net.minecraft.client Minecraft)
           (net.minecraft.client.gui GuiGraphics Font)
           (net.minecraft.client.renderer.texture TextureManager)
           (net.minecraft.client.renderer ShaderInstance)
           (net.minecraft.resources ResourceLocation)
           (com.mojang.blaze3d.vertex PoseStack)
           (com.mojang.blaze3d.systems RenderSystem)
           (cn.li.mc1201.client MinecraftClientAccess GuiGraphicsHelper TextureSizeAccess)
           (cn.li.mc1201.gui.reactive.render StaticShaderSupplier)
           (com.mojang.blaze3d.vertex Tesselator BufferBuilder BufferUploader PoseStack$Pose DefaultVertexFormat VertexFormat$Mode)
           (org.joml Matrix4f)
           (org.lwjgl.opengl GL11)
           (org.joml Quaternionf)))

(defn create-cgui-renderer-runtime
  ([]
   (create-cgui-renderer-runtime {}))
  ([initial-cache]
   {:texture-size-cache (atom initial-cache)}))

;; STB em 8px; :font-size N → N px on screen (typographic bounds, no bake padding in layout).

(def ^:private _cgui-renderer-runtime (delay (create-cgui-renderer-runtime)))

;; Pre-allocated double buffers for blendquad 9-slice vertex coordinates,
;; eliminating per-frame xs/ys vector allocations.
(defonce ^:private quad-xs-buffer (double-array 4))
(defonce ^:private quad-ys-buffer (double-array 4))

;; Shared singletons for zero-allocation tree traversal
(defonce ^:private shared-quaternion (org.joml.Quaternionf.))
(defonce ^:private child-pos-shuttle (double-array 3))  ;; [abs-x, abs-y, cum-scale]

(def ^:dynamic *cgui-renderer-runtime* nil)

(defn current-cgui-renderer-runtime
  []
  (or *cgui-renderer-runtime*
      @_cgui-renderer-runtime))

(defmacro with-cgui-renderer-runtime
  [runtime & body]
  `(binding [*cgui-renderer-runtime* ~runtime]
     ~@body))

(defn call-with-cgui-renderer-runtime
  [runtime f]
  (binding [*cgui-renderer-runtime* runtime]
    (f)))

(defn texture-size-cache-atom
  []
  (:texture-size-cache (current-cgui-renderer-runtime)))

(defn texture-size-cache-snapshot
  []
  @(texture-size-cache-atom))

(defn clear-texture-size-cache!
  []
  (reset! (texture-size-cache-atom) {})
  nil)

(defn reset-texture-size-cache-for-test!
  ([]
   (clear-texture-size-cache!))
  ([cache]
   (reset! (texture-size-cache-atom) cache)
   nil))

(defn- get-texture-size-from-resource
  [resource-location]
  (assets/get-texture-size-from-resource resource-location))

(defn- get-texture-size
  [resource-location]
  (when resource-location
    (let [k (str resource-location)
          cached ((texture-size-cache-snapshot) k)]
      (if cached
        cached
        (let [size (or
                     (try
                       (get-texture-size-from-resource resource-location)
                       (catch Exception _ nil))
                     (try
                       (let [^Minecraft mc (MinecraftClientAccess/getMinecraft)
                             ^TextureManager tm (try (.getTextureManager mc) (catch Exception _ nil))]
                         (when tm
                           (let [dims (TextureSizeAccess/sizeFromManager tm resource-location)]
                             (when dims [(aget dims 0) (aget dims 1)]))))
                       (catch Exception _ nil)))]
          (when size (swap! (texture-size-cache-atom) assoc k size))
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
  (assets/ensure-resource-location v))

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
  [^GuiGraphics gg root ^doubles abs-pos scale left top]
  (when-not (cgui-core/visible? root)
    (throw (ex-info "render-widget! called on invisible widget" {})))
  (let [size (cgui-core/get-size root)
        [w h] size
        x (int (+ left (aget abs-pos 0)))
        y (int (+ top (aget abs-pos 1)))
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
                color-int (unchecked-int (or (:color state) gui-comp/blend-quad-default-color))
                r (/ (double (bit-and (bit-shift-right color-int 16) 0xFF)) 255.0)
                g (/ (double (bit-and (bit-shift-right color-int 8) 0xFF)) 255.0)
                b (/ (double (bit-and color-int 0xFF)) 255.0)
                a (if (pos? (bit-and color-int 0xFF000000))
                    (/ (double (bit-and (bit-shift-right color-int 24) 0xFF)) 255.0)
                    1.0)]
            ;; In-place write to static double buffers — zero allocation
            (aset quad-xs-buffer 0 (double (- x margin)))
            (aset quad-xs-buffer 1 (double x))
            (aset quad-xs-buffer 2 (double (+ x w-int)))
            (aset quad-xs-buffer 3 (double (+ x w-int margin)))
            (aset quad-ys-buffer 0 (double (- y margin)))
            (aset quad-ys-buffer 1 (double y))
            (aset quad-ys-buffer 2 (double (+ y h-int)))
            (aset quad-ys-buffer 3 (double (+ y h-int margin)))
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
                  (dotimes [i 3]
                    (dotimes [j 3]
                      (let [x0 (round-int (aget quad-xs-buffer i))
                            y0 (round-int (aget quad-ys-buffer j))
                            x1 (round-int (aget quad-xs-buffer (inc i)))
                            y1 (round-int (aget quad-ys-buffer (inc j)))
                          tw (max 1 (- x1 x0))
                          th (max 1 (- y1 y0))
                          u (* i cell-w)
                          v (* j cell-h)]
                      (blit-scaled-region! gg blend-tex x0 y0 tw th u v cell-w cell-h 0.0 tex-w tex-h)))))
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
                    a (/ (double (bit-and (bit-shift-right color-int 24) 0xFF)) 255.0)]
                (when (and (pos? src-w) (pos? src-h))
                  (try
                    (RenderSystem/enableBlend)
                    (RenderSystem/defaultBlendFunc)
                    (RenderSystem/setShaderColor (float r) (float g) (float b) (float a))
                    (apply-depth-mode! depth-mode write-depth)
                    (blit-scaled-region! gg tex-loc x y w-int h-int u-px v-px src-w src-h z-level tex-atlas-w tex-atlas-h)
                    (catch Exception e
                      (log/debug "CGUI drawtexture render error:" (.getMessage e)))
                    (finally
                      (RenderSystem/disableDepthTest)
                      (RenderSystem/depthFunc GL11/GL_LEQUAL)
                      (RenderSystem/depthMask true)
                      (RenderSystem/setShaderColor 1.0 1.0 1.0 1.0)))))
              (.fill gg x y (+ x w-int) (+ y h-int) (unchecked-int (or (:color state) 0xFFFFFF)))))

          (kind-matches? kind :rotated-line)
          (let [^ResourceLocation tex-loc (ensure-resource-location (:tex state))
                dx (double (or (:dx state) 0.0))
                dy (double (or (:dy state) 0.0))
                line-w (double (or (:line-w state) 5.5))
                half-w (/ line-w 2.0)
                norm (Math/sqrt (+ (* dx dx) (* dy dy)))
                color-int (unchecked-int (or (:color state) 0xFFFFFFFF))
                ca (/ (double (bit-and (bit-shift-right color-int 24) 0xFF)) 255.0)
                cr (/ (double (bit-and (bit-shift-right color-int 16) 0xFF)) 255.0)
                cg (/ (double (bit-and (bit-shift-right color-int 8) 0xFF)) 255.0)
                cb (/ (double (bit-and color-int 0xFF)) 255.0)]
            (when (and tex-loc (> norm 0.5) (> ca 0.0))
              (try
                (let [^PoseStack ps (.pose gg)
                      ^PoseStack$Pose entry (.last ps)
                      ^Matrix4f pose-matrix (.pose entry)
                      ;; Perpendicular normal for line width (matching upstream drawLine)
                      ndx (/ (- dy) norm) ndy (/ dx norm)
                      nx (* ndx half-w) ny (* ndy half-w)
                      x0 (- x (* ndx half-w)) y0 (- y (* ndy half-w))
                      x1 (+ x dx (* ndx half-w)) y1 (+ y dy (* ndy half-w))
                      ^Tesselator tess (Tesselator/getInstance)
                      ^BufferBuilder bb (.getBuilder tess)]
                  (RenderSystem/enableBlend)
                  (RenderSystem/defaultBlendFunc)
                  (RenderSystem/setShaderColor (float cr) (float cg) (float cb) (float ca))
                  (RenderSystem/setShaderTexture (int 0) tex-loc)
                  (.begin bb VertexFormat$Mode/QUADS DefaultVertexFormat/POSITION_TEX)
                  (.vertex bb pose-matrix (float (- x0 nx)) (float (- y0 ny)) 0.0) (.uv bb (float 0.0) (float 0.0)) (.endVertex bb)
                  (.vertex bb pose-matrix (float (+ x0 nx)) (float (+ y0 ny)) 0.0) (.uv bb (float 0.0) (float 1.0)) (.endVertex bb)
                  (.vertex bb pose-matrix (float (+ x1 nx)) (float (+ y1 ny)) 0.0) (.uv bb (float 1.0) (float 1.0)) (.endVertex bb)
                  (.vertex bb pose-matrix (float (- x1 nx)) (float (- y1 ny)) 0.0) (.uv bb (float 1.0) (float 0.0)) (.endVertex bb)
                  (BufferUploader/drawWithShader (.end bb))
                  (RenderSystem/setShaderColor 1.0 1.0 1.0 1.0))
                (catch Exception e
                  (log/debug "CGUI rotated-line render error:" (.getMessage e))))))

          (kind-matches? kind :shader-progress)
          (let [t0 (:texture-0 state) t1 (:texture-1 state)
                ^ResourceLocation tex-loc-0 (ensure-resource-location t0)
                ^ResourceLocation tex-loc-1 (ensure-resource-location t1)
                ^ShaderInstance si (platform-bridge/resolve-shader (:shader-id state))
                progress (float (or (:progress state) 0.0))]
            (when (and si tex-loc-0 tex-loc-1)
              (try
                (.setSampler si "TexSampler0" tex-loc-0)
                (.setSampler si "TexSampler1" tex-loc-1)
                (RenderSystem/setShader (StaticShaderSupplier. si))
                (when-let [u (.safeGetUniform si "Progress")] (.set u progress))
                ;; Use BufferBuilder to bypass blit() which overrides custom shaders
                (let [^PoseStack ps (.pose gg)
                      ^PoseStack$Pose entry (.last ps)
                      ^Matrix4f pose-matrix (.pose entry)
                      x1 (float x) y1 (float y)
                      x2 (float (+ x w-int)) y2 (float (+ y h-int))
                      ^Tesselator tess (Tesselator/getInstance)
                      ^BufferBuilder bb (.getBuilder tess)]
                  (RenderSystem/setShaderTexture (int 0) tex-loc-0)
                  (RenderSystem/setShaderTexture (int 1) tex-loc-1)
                  (.begin bb VertexFormat$Mode/QUADS DefaultVertexFormat/POSITION_TEX)
                  (.vertex bb pose-matrix x1 y2 0.0) (.uv bb (float 0.0) (float 1.0)) (.endVertex bb)
                  (.vertex bb pose-matrix x2 y2 0.0) (.uv bb (float 1.0) (float 1.0)) (.endVertex bb)
                  (.vertex bb pose-matrix x2 y1 0.0) (.uv bb (float 1.0) (float 0.0)) (.endVertex bb)
                  (.vertex bb pose-matrix x1 y1 0.0) (.uv bb (float 0.0) (float 0.0)) (.endVertex bb)
                  (BufferUploader/drawWithShader (.end bb)))
                (RenderSystem/setShader (StaticShaderSupplier. nil))
                (catch Exception e
                  (log/debug "CGUI shader-progress render error:" (.getMessage e))))))

          (kind-matches? kind :gradient-fill)
          ;; Horizontal gradient glow fill matching upstream
          ;; ACRenderingHelper.drawGlow. Uses multiple fill() bands with
          ;; stepped alpha to approximate a smooth gradient without texture
          ;; assets. Band count is a trade-off: more = smoother but more
          ;; draw calls; 5 bands per side (10 total) gives reasonable
          ;; smoothness for the typical 300px-wide glow line at ~0.25 scale.
          (let [color-center (unchecked-int (or (:color-center state) 0xFFFFFFFF))
                color-edge   (unchecked-int (or (:color-edge state) 0x00FFFFFF))
                bands 5
                half-w (double (/ w-int 2))
                band-w (double (/ half-w bands))
                ;; Zero-allocation primitive ARGB extraction — no closure, no Map
                cc-a (long (bit-and (bit-shift-right color-center 24) 0xFF))
                cc-r (long (bit-and (bit-shift-right color-center 16) 0xFF))
                cc-g (long (bit-and (bit-shift-right color-center 8) 0xFF))
                cc-b (long (bit-and color-center 0xFF))
                ec-a (long (bit-and (bit-shift-right color-edge 24) 0xFF))
                ec-r (long (bit-and (bit-shift-right color-edge 16) 0xFF))
                ec-g (long (bit-and (bit-shift-right color-edge 8) 0xFF))
                ec-b (long (bit-and color-edge 0xFF))]
            ;; Left half: edge (transparent) → center (opaque)
            (dotimes [i bands]
              (let [frac (double (/ (double i) bands))
                    a (long (+ ec-a (long (* (- cc-a ec-a) frac))))
                    r (long (+ ec-r (long (* (- cc-r ec-r) frac))))
                    g (long (+ ec-g (long (* (- cc-g ec-g) frac))))
                    b (long (+ ec-b (long (* (- cc-b ec-b) frac))))
                    argb (unchecked-int (bit-or (bit-shift-left a 24) (bit-shift-left r 16) (bit-shift-left g 8) b))
                    bx (int (Math/round (+ (double x) (* (double i) band-w))))]
                (.fill gg bx (int y) (int (Math/round (+ (double bx) band-w))) (int (+ y h-int)) argb)))
            ;; Right half: center (opaque) → edge (transparent)
            (dotimes [i bands]
              (let [frac (double (/ (double i) bands))
                    a (long (+ cc-a (long (* (- ec-a cc-a) frac))))
                    r (long (+ cc-r (long (* (- ec-r cc-r) frac))))
                    g (long (+ cc-g (long (* (- ec-g cc-g) frac))))
                    b (long (+ cc-b (long (* (- ec-b cc-b) frac))))
                    argb (unchecked-int (bit-or (bit-shift-left a 24) (bit-shift-left r 16) (bit-shift-left g 8) b))
                    bx (int (Math/round (+ (double x) half-w (* (double i) band-w))))]
                (.fill gg bx (int y) (int (Math/round (+ (double bx) band-w))) (int (+ y h-int)) argb))))

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
                ^int color (font-api/normalize-color-int (or (:color state) 0xFFFFFF))
                font-size (double (or (:font-size state) 8.0))
                font-scale (* (/ font-size (font-api/get-msdf-base-height)) (double scale))
                align (or (:align state) :left)
                height-align (or (:height-align state) :top)
                x-offset (double (or (:x-offset state) 0.0))
                y-offset (double (or (:y-offset state) 0.0))
                z-level (double (or (:z-level state) 0.0))
                emit? (if (contains? state :emit?) (:emit? state) true)
                font-desc (when-let [fname (:font state)]
                            (font-api/get-font fname))
                text-w (* (font-api/text-width font-desc text font-size) (double scale))
                text-h (* font-size (double scale))
                aligned-dx (case align
                             :center (/ (- (double w-int) text-w) 2.0)
                             :right  (- (double w-int) text-w)
                             0.0)
                aligned-dy (case height-align
                             :center (/ (- (double h-int) text-h) 2.0)
                             :bottom (- (double h-int) text-h)
                             0.0)
                text-sx (+ (double x) aligned-dx x-offset)
                text-sy (+ (double y) aligned-dy y-offset)
                ^PoseStack ps (.pose gg)]
            (when (seq text)
              (let [pushed-flag (boolean-array 1 [false])
                    scissor-flag (boolean-array 1 [false])]
                (try
                  (.pushPose ps)
                  (aset pushed-flag 0 true)
                  (when (not= 0.0 z-level)
                    (.translate ps 0.0 0.0 z-level))
                  (when emit?
                    (.enableScissor gg x y (+ x w-int) (+ y h-int))
                    (aset scissor-flag 0 true))
                  (.translate ps text-sx text-sy 0.0)
                  (.scale ps (float scale) (float scale) 1.0)
                  (font-api/draw-text! gg font-desc text 0 0 font-size color :left
                                       (boolean (:shadow? state)))
                  (catch Exception e
                    (log/warn "CGUI textbox render error:" (.getMessage e)))
                  (finally
                    (when (aget scissor-flag 0)
                      (try (.disableScissor gg) (catch Exception _ nil)))
                    (when (aget pushed-flag 0)
                      (try (.popPose ps) (catch Exception _ nil)))))))
            (when (and (:editable? state)
                       (some-> @(:metadata root) :focused?))
              (let [pushed-flag (boolean-array 1 [false])]
                (try
                  (let [caret-visible? (< (rem (System/currentTimeMillis) 1000) 500)
                        caret-local-x text-w
                        ^net.minecraft.client.gui.Font mc-font (MinecraftClientAccess/getFont)]
                    (when caret-visible?
                      (.pushPose ps)
                      (aset pushed-flag 0 true)
                      (when (not= 0.0 z-level)
                        (.translate ps 0.0 0.0 z-level))
                      (.translate ps (+ text-sx caret-local-x) text-sy 0.0)
                      (.scale ps (float font-scale) (float font-scale) 1.0)
                      (.drawString gg mc-font "|" (int 0) (int 0)
                                   color (boolean (:shadow? state)))))
                  (catch Exception _ nil)
                  (finally
                    (when (aget pushed-flag 0)
                      (try (.popPose ps) (catch Exception _ nil))))))))

          (kind-matches? kind :progressbar)
          (let [progress (double (or (:progress state) 0.0))
                dir (:direction state :horizontal)
                color-full (unchecked-int (or (:color-full state) 0x00FF00))
                color-empty (unchecked-int (or (:color-empty state) 0x404040))]
            (if (= dir :vertical)
              (let [fill-h (round-int (* progress h-int))]
                (.fill gg x y (+ x w-int) (+ y h-int (- fill-h)) color-empty)
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

          (kind-matches? kind :shader-quad)
          (let [shader (platform-bridge/resolve-shader (:shader-id state))
                tex-0 (ensure-resource-location (:texture-0 state))
                tex-1 (ensure-resource-location (:texture-1 state))
                progress (float (or (:progress state) 0.0))]
            (if shader
              (try
                (.setSampler ^ShaderInstance shader "TexSampler0" tex-0)
                (when tex-1 (.setSampler ^ShaderInstance shader "TexSampler1" tex-1))
                (RenderSystem/setShader (StaticShaderSupplier. shader))
                (when-let [u (.safeGetUniform ^ShaderInstance shader "Progress")]
                  (.set u progress))
                (.blit gg tex-0 x y 0 0 w-int h-int w-int h-int)
                (RenderSystem/setShader (StaticShaderSupplier. nil))
                (catch Exception e
                  (log/debug "CGUI shader-quad render error:" (.getMessage e))))
              ;; Fallback: render without shader
              (if tex-0
                (try
                  (RenderSystem/enableBlend)
                  (RenderSystem/defaultBlendFunc)
                  (RenderSystem/setShaderColor 1.0 1.0 1.0 1.0)
                  (blit-scaled-region! gg tex-0 x y w-int h-int 0 0 w-int h-int 0.0 w-int h-int)
                  (catch Exception _))
                (.fill gg x y (+ x w-int) (+ y h-int) 0xFF2A2A2A))))

          (kind-matches? kind :reactive-embed)
          (when-let [^cn.li.mcmod.uipojo.runtime.UiRt rt (:rt state)]
            (try
              (let [^PoseStack ps (.pose gg)]
                (.pushPose ps)
                (.translate ps (double x) (double y) 0.0)
                (reactive-render/render-embedded-runtime!
                  gg rt 0.0 0.0 (double w-int) (double h-int) 0.0)
                (.popPose ps))
              (catch Exception e
                (log/debug "CGUI reactive-embed render error:" (.getMessage e)))))

          :else nil)))))

(defn- compute-child-abs-pos!
  "Write child absolute position into child-pos-shuttle: [0]=abs-x, [1]=abs-y, [2]=cum-scale.
   Returns nil. Zero-allocation — no PersistentVector return value."
  [^doubles parent-abs-pos parent-size ^double parent-scale child]
  (let [own-scale (double (or @(:scale child) 1.0))
        cum-scale (* parent-scale own-scale)
        px (double (aget parent-abs-pos 0))
        py (double (aget parent-abs-pos 1))
        [wx wy] (cgui-core/get-pos child)
        [pw ph] (or parent-size [0 0])
        [w h] (cgui-core/get-size child)
        tm (get @(:metadata child) :transform-meta {})
        pivot-x (double (or (:pivot-x tm) 0.0))
        pivot-y (double (or (:pivot-y tm) 0.0))
        align-w (:align-width tm)
        align-h (:align-height tm)
        sw (* (double w) own-scale)
        sh (* (double h) own-scale)
        align-offset-x (double (case align-w :center (/ (- (double pw) sw) 2.0) :right (- (double pw) sw) 0.0))
        align-offset-y (double (case align-h :center (/ (- (double ph) sh) 2.0) :middle (/ (- (double ph) sh) 2.0) :bottom (- (double ph) sh) 0.0))
        pivot-shift-x (* pivot-x (double w))
        pivot-shift-y (* pivot-y (double h))
        child-x (+ align-offset-x (double wx) (- pivot-shift-x))
        child-y (+ align-offset-y (double wy) (- pivot-shift-y))]
    (aset child-pos-shuttle 0 (+ px (double (Math/round (* child-x parent-scale)))))
    (aset child-pos-shuttle 1 (+ py (double (Math/round (* child-y parent-scale)))))
    (aset child-pos-shuttle 2 cum-scale)
    nil))

(defn- render-widget-tree!
  "Recursively render a widget and its children.
  When the widget has :clip-children? true in its metadata, enables
  scissor to clip child content to the widget's visual bounds.
  When the widget has a :transform component, applies GL transform
  to the widget and all its children (push/pop per widget)."
  [^GuiGraphics gg widget ^doubles abs-pos scale left top]
  (let [components @(:components widget)
        transform-comp (first (filter #(= (or (:kind %) (::kind %)) :transform) components))
        has-transform? (boolean transform-comp)
        ^PoseStack ps (.pose gg)
        abs-x (aget abs-pos 0)
        abs-y (aget abs-pos 1)
        [w h] (cgui-core/get-size widget)
        w-int (round-int (* (double w) scale))
        h-int (round-int (* (double h) scale))]
    ;; Push transform pose BEFORE rendering widget + children
    (when has-transform?
      (let [state @(component-state transform-comp)
            tx (double (or (:translate-x state) 0.0))
            ty (double (or (:translate-y state) 0.0))
            sx (double (or (:scale-x state) 1.0))
            sy (double (or (:scale-y state) 1.0))
            rot (double (or (:rotation state) 0.0))
            cx (+ (double abs-x) (/ (double w-int) 2.0))
            cy (+ (double abs-y) (/ (double h-int) 2.0))]
        (.pushPose ps)
        (.translate ps cx cy 0.0)
        (when (not= 0.0 rot)
          ;; Rotate around Z-axis (clockwise angle in radians) — AOT-safe shared singleton
          (let [^org.joml.Quaternionf q shared-quaternion]
            (.identity q)
            (.rotationZ q (float rot))
            (.mulPose ps q)))
        (.scale ps (float sx) (float sy) 1.0)
        (.translate ps (- cx) (- cy) 0.0)))
    ;; Render own components
    (render-widget! gg widget abs-pos scale left top)
    ;; Render children (with optional scissor clipping)
    (let [children (cgui-core/get-widgets widget)
          clip? (get @(:metadata widget) :clip-children? false)
          wx (int (+ left (int abs-x)))
          wy (int (+ top (int abs-y)))]
      (when (and clip? (seq children))
        (.enableScissor gg wx wy (+ wx w-int) (+ wy h-int)))
      (doseq [c children]
        (when (cgui-core/visible? c)
          (try
            ;; In-place write to child-pos-shuttle
            (compute-child-abs-pos! abs-pos [w h] scale c)
            ;; Snapshot before recursing: child's subtree will overwrite shuttle
            (let [c-scale (aget child-pos-shuttle 2)
                  local-frame (double-array 2 [(aget child-pos-shuttle 0)
                                               (aget child-pos-shuttle 1)])]
              (render-widget-tree! gg c local-frame c-scale left top))
            (catch Exception e
              (log/error "[RENDER-CHILD] " (.getMessage e) " child=" (pr-str (try (cgui-core/get-pos c) (catch Exception _ "?"))))))))
      (when (and clip? (seq children))
        (.disableScissor gg)))
    ;; Pop transform pose AFTER widget + children rendered
    (when has-transform?
      (.popPose ps))))

(defn render-tree!
  [^GuiGraphics gg root left top]
  (when root
    (let [visible (cgui-core/visible? root)
          size (cgui-core/get-size root)]
      (when (not (get @(:metadata root) :cgui-render-debug-logged false))
        (let [widgets (traversal/collect-widgets-z-ordered root [0 0] 1.0 nil)]
          (log/info "CGUI render-tree! root visible:" visible "size:" size
                    "widget count:" (count widgets)))
        (swap! (:metadata root) assoc :cgui-render-debug-logged true))
      ;; Render root's own components (root at origin)
      (let [root-abs-pos (double-array 2 [0.0 0.0])]
        (render-widget! gg root root-abs-pos 1.0 left top))
      ;; Render root's direct children recursively
      (let [[w h] size]
        (doseq [c (cgui-core/get-widgets root)]
          (when (cgui-core/visible? c)
            (try
              (compute-child-abs-pos! (double-array 2 [0.0 0.0]) [w h] 1.0 c)
              (let [c-scale (aget child-pos-shuttle 2)
                    local-frame (double-array 2 [(aget child-pos-shuttle 0)
                                                 (aget child-pos-shuttle 1)])]
                (render-widget-tree! gg c local-frame c-scale left top))
              (catch Exception e
                (log/error "[RENDER-ROOT-CHILD] " (.getMessage e))))))))))

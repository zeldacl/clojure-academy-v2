(ns my-mod.forge1201.gui.cgui-runtime
  "Forge 1.20.1 CGUI runtime: resize, render, and event dispatch for the pure Clojure
   widget tree. Used by the CGUI screen proxy to drive rendering and input."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [my-mod.gui.cgui :as cgui]
    [my-mod.platform.resource :as res]
    [my-mod.config.modid :as modid]
    [my-mod.util.log :as log])
  (:import
    (net.minecraft.client.gui GuiGraphics)
    (net.minecraft.client.gui Font)
    (net.minecraft.client Minecraft)
    (net.minecraft.client.resources.language I18n)
    (net.minecraft.resources ResourceLocation)
    (com.mojang.blaze3d.systems RenderSystem)
    (javax.imageio ImageIO)
    (org.lwjgl.opengl GL11)
    ))

  (defonce ^:private texture-size-cache (atom {}))

  (defn- resource-location->asset-path
    [resource-location]
    (when resource-location
      (let [[ns path] (str/split (str resource-location) #":" 2)]
        (when (and ns path)
          (str "assets/" ns "/" path)))))

  (defn- get-texture-size-from-resource
    [resource-location]
    (when-let [asset-path (resource-location->asset-path resource-location)]
      (when-let [resource (or (io/resource asset-path)
                              (io/resource asset-path (.getClassLoader (class get-texture-size-from-resource))))]
        (with-open [stream (io/input-stream resource)]
          (when-let [image (ImageIO/read stream)]
            [(.getWidth image) (.getHeight image)])))))

  (defn- get-texture-size
    "Obtain pixel size [width height] for `resource-location` by reading the PNG
     from this mod's resources. Returns [w h] or nil."
    [^ResourceLocation resource-location]
    (when resource-location
      (let [k (str resource-location)
            cached (@texture-size-cache k)]
        (if cached
          cached
          (let [size (try
                        (get-texture-size-from-resource resource-location)
                        (catch Exception _ nil))]
            (when size
              (swap! texture-size-cache assoc k size))
            size)))))

        (defn- apply-depth-mode!
          "Configure RenderSystem depth test/mask according to `mode` and `write-depth`.
           Uses RenderSystem to change state; GL11 constants are passed as args where needed.
           There is no RenderSystem-provided enum for depth funcs, so GL11 constants are used here
           but centralized in one helper to make future changes easier."
          [mode write-depth]
          (RenderSystem/depthMask write-depth)
          (case mode
            :equals (do (RenderSystem/enableDepthTest) (RenderSystem/depthFunc GL11/GL_EQUAL))
            :always (do (RenderSystem/enableDepthTest) (RenderSystem/depthFunc GL11/GL_ALWAYS))
            ;; default: none
            (RenderSystem/disableDepthTest)))

(defn- component-kind [c]
  (or (:kind c) (::kind c) :unknown))

(defn- kind-matches? [kind expected]
  (or (= kind expected) (= (keyword (name kind)) expected)))

(defn- component-state [c]
  (or (:state c) (atom {})))

(defn- ensure-resource-location [v]
  (cond
    (nil? v) nil
    (instance? ResourceLocation v) v
    (string? v) (if (re-find #":" v)
                   (let [[ns path] (str/split v #":" 2)]
                     (res/invoke-resource-location ns path))
                   ;; If the string is an absolute assets path like "assets/ns/..",
                   ;; extract namespace and path and resolve explicitly.
                   (cond
                     (str/starts-with? v "assets/")
                     (let [after (subs v (count "assets/"))
                           parts (str/split after #"/" 2)
                           ns (first parts)
                           path (second parts)]
                       (if (and ns path)
                         (res/invoke-resource-location ns path)
                         (res/invoke-resource-location nil v)))

                     ;; No namespace: resolve explicitly with current MOD-ID
                     :else
                     (res/invoke-resource-location modid/MOD-ID v)))
    :else nil))

(defn- collect-widgets-z-ordered
  "Depth-first traversal of widget tree, returning widgets in draw order (parents before children).
   Only visible widgets. Each node is [widget absolute-pos cumulative-scale].
   This version accumulates parent scale so children are rendered with parent's scale multiplied.
   Additionally applies :transform-meta alignment and pivot when computing absolute positions so
   child widgets inherit the adjusted coordinates." 
  [root abs-pos parent-scale parent-size]
  (when (and root (cgui/visible? root))
    (let [pos (cgui/get-pos root)
          own-scale (double (or @(:scale root) 1.0))
          cum-scale (* parent-scale own-scale)
          [px py] abs-pos
          [wx wy] pos
          ;; parent-size is the logical size [pw ph] of the parent widget (unscaled).
          [pw ph] (or parent-size [0 0])
          ;; widget size (logical units)
          [w h] (cgui/get-size root)
          ;; transform-meta may include pivot-x/pivot-y and align-width/align-height
          tm (get @(:metadata root) :transform-meta {})
          pivot-x (or (:pivot-x tm) 0.0)
          pivot-y (or (:pivot-y tm) 0.0)
          align-w (when-let [a (:align-width tm)] (-> a name str/lower-case keyword))
          align-h (when-let [a (:align-height tm)] (-> a name str/lower-case keyword))
          ;; compute alignment offsets in logical units relative to parent size
          align-offset-x (case align-w
                           :center (int (Math/round (double (/ (- pw w) 2.0))))
                           :right  (int (Math/round (double (- pw w))))
                           ;; default :left or nil
                           0)
          align-offset-y (case align-h
                           :center (int (Math/round (double (/ (- ph h) 2.0))))
                           :bottom (int (Math/round (double (- ph h))))
                           ;; default :top or nil
                           0)
          ;; pivot is fraction of widget size; shift top-left so pivot point is at position
          pivot-shift-x (* pivot-x w)
          pivot-shift-y (* pivot-y h)
          ;; absolute position for this widget (logical units)
          abs-x (+ px align-offset-x wx (- pivot-shift-x))
          abs-y (+ py align-offset-y wy (- pivot-shift-y))
          children (cgui/get-widgets root)
          next-parent-size [w h]
          next-pos [abs-x abs-y]]
      (cons [root next-pos cum-scale]
            (mapcat #(collect-widgets-z-ordered % next-pos cum-scale next-parent-size) children)))))

    (def ^:private DRAG-TIME-TOL-MS 100)

(defn resize-root!
  "Update root widget with the current screen/container size.

   - For layouts that already have an explicit size (XML/TechUI pages), we *do not*
     overwrite the widget's logical width/height, otherwise textures from page_wireless.xml
     等会被强行拉伸。
   - We always record the latest screen size into root metadata so alignment logic
     can choose to center the root if needed."
  [root width height]
  (when root
    (let [[w h] (cgui/get-size root)]
      ;; Only fall back to screen size when the root has no intrinsic size.
      (when (and (zero? (double w)) (zero? (double h)))
        (cgui/set-size! root width height))
      (swap! (:metadata root) assoc :screen-size [width height])))
  root)

(defn- blit-scaled-region!
  "Draw a texture region scaled to the target widget size via pose matrix.

   Applies a pose-matrix scale of (target/src) so that src-w x src-h pose-space
   units map to target-w x target-h screen pixels.  The blit call always uses
   src-w x src-h as the quad dimensions (in scaled pose space), covering the
   full source region without tiling.

   tex-atlas-w / tex-atlas-h are the full PNG dimensions used for UV
   normalization.  For standalone textures (no sub-region) these equal src-w
   and src-h.  For sprite-sheet sub-regions pass the atlas width/height so the
   UV fraction is computed correctly."
  [^GuiGraphics gg tex-loc x y target-w target-h u-px v-px src-w src-h z-level tex-atlas-w tex-atlas-h]
  (let [safe-src-w   (max 1 (int src-w))
        safe-src-h   (max 1 (int src-h))
        safe-atlas-w (max 1 (int tex-atlas-w))
        safe-atlas-h (max 1 (int tex-atlas-h))
        scale-x      (/ (double (max 1 target-w)) (double safe-src-w))
        scale-y      (/ (double (max 1 target-h)) (double safe-src-h))
        ps           (.pose gg)]
    (.pushPose ps)
    (when-not (zero? z-level)
      (.translate ps 0.0 0.0 (double z-level)))
    (.translate ps (double x) (double y) 0.0)
    ;; Scale pose so that src-w x src-h units = target-w x target-h screen px.
    (.scale ps (float scale-x) (float scale-y) 1.0)
    ;; 1.20.1 official: use the public GuiGraphics#blit overload directly.
    ;; We keep a single overload to avoid Reflector-based dynamic dispatch.
    (.blit gg tex-loc (int 0) (int 0)
           (int u-px) (int v-px)
           (int safe-src-w) (int safe-src-h))
    (.popPose ps)))
  
 (defn render-widget!
  "Render a single widget at the given absolute position and scale using GuiGraphics.
   Draws :drawtexture, :textbox, :progressbar, :outline, :tint as applicable."
  [^GuiGraphics gg root [abs-x abs-y] scale left top]
  (when-not (cgui/visible? root)
    (throw (ex-info "render-widget! called on invisible widget" {})))
  (let [size (cgui/get-size root)
        [w h] size
        x (int (+ left abs-x))
        y (int (+ top abs-y))
        w-int (int (Math/round (* (double w) scale)))
        h-int (int (Math/round (* (double h) scale)))
        components @(:components root)]
    (doseq [c components]
      (let [kind (component-kind c)
            state @(component-state c)]
        (cond
          (kind-matches? kind :blendquad)
          (let [blend-tex (ensure-resource-location (:blend-tex state))
                line-tex (ensure-resource-location (:line-tex state))
                margin (double (or (:margin state) 4.0))
                color-int (unchecked-int (or (:color state) 0x80FFFFFF))
                r (/ (double (bit-and (bit-shift-right color-int 16) 0xFF)) 255.0)
                g (/ (double (bit-and (bit-shift-right color-int 8) 0xFF)) 255.0)
                b (/ (double (bit-and color-int 0xFF)) 255.0)
                a (if (pos? (bit-and color-int 0xFF000000))
                    (/ (double (bit-and (bit-shift-right color-int 24) 0xFF)) 255.0)
                    1.0)
                ;; replicate 1.12: xs=[x-margin,x,x+w,x+w+margin], ys=[y-margin,y,y+h,y+h+margin]
                xs [(double (- x margin)) (double x) (double (+ x w-int)) (double (+ x w-int margin))]
                ys [(double (- y margin)) (double y) (double (+ y h-int)) (double (+ y h-int margin))]]
            (when blend-tex
              (try
                (RenderSystem/enableBlend)
                (RenderSystem/defaultBlendFunc)
                (RenderSystem/setShaderColor (float r) (float g) (float b) (float a))
                (apply-depth-mode! :none false)

                ;; nine-slice (3x3)
                (let [tex-size (get-texture-size blend-tex)
                      tex-w (max 1 (int (or (first tex-size) 48)))
                      tex-h (max 1 (int (or (second tex-size) 48)))
                      cell-w (max 1 (int (Math/floor (/ tex-w 3.0))))
                      cell-h (max 1 (int (Math/floor (/ tex-h 3.0))))]
                  (doseq [i (range 3)
                          j (range 3)]
                    (let [x0 (int (Math/round (double (nth xs i))))
                          y0 (int (Math/round (double (nth ys j))))
                          x1 (int (Math/round (double (nth xs (inc i)))))
                          y1 (int (Math/round (double (nth ys (inc j)))))
                          tw (max 1 (- x1 x0))
                          th (max 1 (- y1 y0))
                          u (* i cell-w)
                          v (* j cell-h)]
                      (blit-scaled-region! gg blend-tex x0 y0 tw th u v cell-w cell-h 0.0 tex-w tex-h))))

                ;; line overlays (1.12 HudUtils.rect on lineTex)
                (when line-tex
                  (let [mrg 3.2
                        top-x (int (Math/round (double (- x mrg))))
                        top-y (int (Math/round (double (- y 8.6))))
                        top-w (int (Math/round (double (+ w-int (* mrg 2.0)))))
                        top-h (int (Math/round (double 12.0)))
                        bot-x (int (Math/round (double (- x mrg))))
                        bot-y (int (Math/round (double (+ y h-int -2.0))))
                        bot-w (int (Math/round (double (+ w-int (* mrg 2.0)))))
                        bot-h (int (Math/round (double 8.0)))
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
                tex-w (when (and tex-size (number? (first tex-size)))
                  (int (first tex-size)))
                tex-h (when (and tex-size (number? (second tex-size)))
                  (int (second tex-size)))
                has-uv? (and (sequential? uv) (>= (count uv) 4))
                [u v uw uh] (if has-uv?
                  uv
                  [0 0 (or tex-w w-int) (or tex-h h-int)])
                    fractional? (and (number? u) (number? v) (number? uw) (number? uh)
                                      (<= (double u) 1.0) (<= (double v) 1.0)
                                      (<= (double uw) 1.0) (<= (double uh) 1.0))
                    basis-w (if (and tex-size (number? (first tex-size))) (first tex-size) w-int)
                    basis-h (if (and tex-size (number? (second tex-size))) (second tex-size) h-int)
                    u-px (if fractional? (int (Math/round (* (double u) (double basis-w)))) (int u))
                    v-px (if fractional? (int (Math/round (* (double v) (double basis-h)))) (int v))
                src-w (if fractional?
                  (int (Math/round (* (double uw) (double basis-w))))
                  (int uw))
                src-h (if fractional?
                  (int (Math/round (* (double uh) (double basis-h))))
                  (int uh))
                    ;; Full atlas dimensions for UV normalization.
                    ;; When no sub-region UV is used src == atlas; prefer the
                    ;; actual PNG size so the UV fraction covers the full region.
                    tex-atlas-w (or tex-w src-w)
                    tex-atlas-h (or tex-h src-h)
                    ;; optional state keys to approximate old behavior
                    color-int (or (:color state) 0xFFFFFF)
                    z-level (or (:z-level state) 0.0)
                    depth-mode (or (:depth-test-mode state) :none)
                    write-depth (and (boolean (:write-depth state))
                                     (not= depth-mode :none))
                    ;; compute color components
                    r (/ (double (bit-and (bit-shift-right color-int 16) 0xFF)) 255.0)
                    g (/ (double (bit-and (bit-shift-right color-int 8) 0xFF)) 255.0)
                    b (/ (double (bit-and color-int 0xFF)) 255.0)
                    a (if (pos? (bit-and color-int 0xFF000000))
                        (/ (double (bit-and (bit-shift-right color-int 24) 0xFF)) 255.0)
                        1.0)]
                (when (and (pos? src-w) (pos? src-h))
                  (try
                    ;; setup blending and shader color
                    (RenderSystem/enableBlend)
                    (RenderSystem/defaultBlendFunc)
                    (RenderSystem/setShaderColor (float r) (float g) (float b) (float a))

                    ;; depth test / depth mask handling (centralized)
                    (apply-depth-mode! depth-mode write-depth)

                    ;; draw the source region scaled to the widget's target size
                    (blit-scaled-region! gg tex-loc x y w-int h-int u-px v-px src-w src-h z-level tex-atlas-w tex-atlas-h)

                    ;; restore depth/blend/shader state to sane defaults
                    (RenderSystem/disableDepthTest)
                    (RenderSystem/depthFunc GL11/GL_LEQUAL)
                    (RenderSystem/depthMask true)
                    (RenderSystem/setShaderColor 1.0 1.0 1.0 1.0)
                    (catch Exception e
                      (log/debug "CGUI drawtexture render error:" (.getMessage e))))))
              ;; no texture -> fallback to solid rect
              (.fill gg x y (+ x w-int) (+ y h-int) (unchecked-int (or (:color state) 0xFFFFFF)))))

          (kind-matches? kind :textbox)
            (let [raw-text (str (or (:text state) ""))
                localized? (boolean (:localized? state))
                raw-text (if (and localized? (seq raw-text))
                           (try
                             (net.minecraft.client.resources.language.I18n/get raw-text (object-array 0))
                             (catch Throwable _ raw-text))
                           raw-text)
                ^String text (if (and (:masked? state) (seq raw-text))
                       (apply str (repeat (count raw-text) \*))
                       raw-text)
                color (unchecked-int (or (:color state) 0xFFFFFF))
                ^Font font (.font (Minecraft/getInstance))]
            (when (seq text)
              (.drawString gg font text (int x) (int y) color))
            ;; caret rendering for editable textboxes when focused
            (when (and (:editable? state)
                       (some-> @(:metadata root) :focused?) )
              (try
                (let [^Font font (.font (Minecraft/getInstance))
                      ;; compute caret x at end of text for now
                      caret-visible? (< (mod (System/currentTimeMillis) 1000) 500)
                      caret-x (+ (int x) (int (.width font text)))]
                  (when caret-visible?
                    (.drawString gg font "|" caret-x (int y) color)))
                (catch Exception _ nil))))

          (kind-matches? kind :progressbar)
          (let [progress    (double (or (:progress state) 0.0))
                dir         (:direction state :horizontal)
                color-full  (unchecked-int (or (:color-full state) 0x00FF00))
                color-empty (unchecked-int (or (:color-empty state) 0x404040))]
            (if (= dir :vertical)
              (let [fill-h (int (Math/round (* progress h-int)))]
                (.fill gg x (+ y h-int (- fill-h)) (+ x w-int) (+ y h-int) color-empty)
                (.fill gg x (+ y h-int (- fill-h)) (+ x w-int) (+ y h-int) color-full))
              (let [fill-w (int (Math/round (* progress w-int)))]
                (.fill gg x y (+ x w-int) (+ y h-int) color-empty)
                (.fill gg x y (+ x fill-w) (+ y h-int) color-full))))

          (kind-matches? kind :outline)
          (let [outline-color (unchecked-int (or (:color state) 0xFFFFFF))
                width         (double (or (:width state) 1.0))
                ww            (int (Math/max 1.0 width))]
            (.fill gg x y (+ x w-int) (+ y ww) outline-color)
            (.fill gg x (+ y h-int (- ww)) (+ x w-int) (+ y h-int) outline-color)
            (.fill gg x y (+ x ww) (+ y h-int) outline-color)
            (.fill gg (+ x w-int (- ww)) y (+ x w-int) (+ y h-int) outline-color))

          (kind-matches? kind :tint)
          nil

          :else
          nil)))))

          

(defn render-tree!
  "Render the entire widget tree rooted at root. left and top are the container screen's
   leftPos and topPos (used to transform widget coordinates into screen space)."
  [^GuiGraphics gg root left top]
  (when root
    ;; Use the widget tree's own logical coordinates without applying additional
    ;; screen-size based alignment here. XML/TechUI layouts already encode all
    ;; positions/sizes explicitly, and extra alignment tends to distort them.
    (doseq [[widget [abs-x abs-y] scale]
            (collect-widgets-z-ordered root [0 0] 1.0 nil)]
      (try
        (render-widget! gg widget [abs-x abs-y] scale left top)
        (catch Exception e
          (log/debug "CGUI render widget error:" (.getMessage e)))))))

(defn hit-test
  "Return the deepest visible widget that contains point (mx, my). Coordinates are in
   screen space; left and top are the container's leftPos and topPos."
  [root mx my left top]
  (let [mx-rel (- mx left)
        my-rel (- my top)]
    (loop [stack (list [root 0 0])
           best nil]
      (if (empty? stack)
        best
        (let [[node ox oy] (peek stack)
              rest-stack (pop stack)]
          (if (or (nil? node) (not (cgui/visible? node)))
            (recur rest-stack best)
            (let [pos (cgui/get-pos node)
                  size (cgui/get-size node)
                  [wx wy] pos
                  [w h] size
                  x0 (+ ox wx)
                  y0 (+ oy wy)
                  x1 (+ x0 w)
                  y1 (+ y0 h)
                  inside (and (>= mx-rel x0) (< mx-rel x1) (>= my-rel y0) (< my-rel y1))]
              ;; (if inside
              ;;   (let [children (cgui/get-widgets node)
              ;;         child-stack (reduce (fn [acc child]
              ;;                              (conj acc [child x0 y0]))
              ;;                            rest-stack (reverse children))]
              ;;     (recur child-stack node))
              ;;   (recur rest-stack best)) 
              (recur (reduce (fn [acc child]
                               (conj acc [child x0 y0]))
                             rest-stack
                             (reverse (cgui/get-widgets node)))
                     (if inside node best)) ; 即使 parent 不包含，也继续检查 children
              )))))))

(defn frame-tick!
  "Emit :frame events to all widgets in the tree (depth-first). event map can include
   :partial-ticks and other frame data."
  [root event]
  (when root
    (doseq [[widget _ _] (collect-widgets-z-ordered root [0 0] 1.0 nil)]
      (try
        (cgui/emit-widget-event! widget :frame event)
        (catch Exception _ nil)))
    ;; drag stop timeout handling: if no drag update within tolerance, emit drag-stop
    (let [m @(:metadata root)
          dnode-atom (:dragging-node m)
          last-drag-atom (:last-drag-time m)]
      (when (and dnode-atom @dnode-atom)
        (let [now (System/currentTimeMillis)
              last @last-drag-atom]
          (when (and last (> (- now last) DRAG-TIME-TOL-MS))
            (try
              (let [dnode @dnode-atom]
                (cgui/emit-widget-event! dnode :drag-stop {:time now})
                (reset! dnode-atom nil)
                (reset! last-drag-atom 0))
              (catch Exception _ nil))))))))

(defn mouse-click!
  "Hit-test at (mx, my), then emit :left-click or :right-click to the hit widget.
   left/top are container leftPos/topPos. button: 0 left, 1 right."
  [root mx my left top button]
  (when-let [hit (hit-test root mx my left top)]
    ;; set focus on left click
    (when (== 0 button)
      (cgui/gain-focus! root hit))
    (log/debug "CGUI mouse-click! mx:" mx "my:" my "left:" left "top:" top "button:" button)
    (cgui/emit-widget-event!
     hit
     (if (== 0 button) :left-click :right-click)
     {:x mx :y my :button button})))

(defn mouse-drag!
  "Emit :drag to the widget that was hit (caller can track which widget was pressed).
   For simplicity we re hit-test and emit to that widget."
  [root mx my left top]
  (when-let [hit (hit-test root mx my left top)]
    ;; initialize dragging node if absent
    (let [m @(:metadata root)
          dnode-atom (:dragging-node m)
          last-drag-atom (:last-drag-time m)
          start-atom (:last-start-time m)
          now (System/currentTimeMillis)]
      (when (and dnode-atom (nil? @dnode-atom))
        (reset! dnode-atom hit)
        (reset! start-atom now)
        (cgui/emit-widget-event! hit :drag-start {:x mx :y my :time now}))
      ;; emit continuous drag
      (when dnode-atom
        (reset! last-drag-atom now)))
    (cgui/emit-widget-event! hit :drag {:x mx :y my})))

(defn key-input!
  "Emit :key to the focused widget or root. keyCode and scanCode are int, typedChar is char.
   Caller can track focus; for now we emit to root so at least key handlers on root get it."
  [root keyCode scanCode typedChar]
  (when root
    (let [m @(:metadata root)
          focus-atom (:cgui-focus m)
          focus (when focus-atom @focus-atom)
          target (or focus root)]
      (cgui/emit-widget-event! target :key {:keyCode keyCode :scanCode scanCode :typedChar typedChar})
      ;; Minimal TextBox editing support for focused widget.
      ;; This drives :change-content and :confirm-input events used by TechUI property editors.
      (when focus
        (when-let [tb (cgui/get-widget-component focus :textbox)]
          (let [st (:state tb)]
            (when (and st (:editable? @st))
              (let [enter-keys #{257 335 28}     ;; GLFW_ENTER, GLFW_KP_ENTER, legacy
                    backspace-keys #{259 14}     ;; GLFW_BACKSPACE, legacy
                    ;; Note: charTyped path passes typedChar with keyCode=0
                    has-char? (and typedChar (not= typedChar (char 0)))
                    curr (str (or (:text @st) ""))]
                (cond
                  (contains? backspace-keys keyCode)
                  (when (pos? (count curr))
                    (swap! st assoc :text (subs curr 0 (dec (count curr))))
                    (cgui/emit-widget-event! focus :change-content {:value (str (:text @st))}))

                  (contains? enter-keys keyCode)
                  (cgui/emit-widget-event! focus :confirm-input {:value curr})

                  has-char?
                  (do
                    (swap! st assoc :text (str curr typedChar))
                    (cgui/emit-widget-event! focus :change-content {:value (str (:text @st))}))

                  :else
                  nil)))))))))

(defn dispose!
  "Release any CGUI state. Currently a no-op; widget tree is GC'd when screen is closed."
  [_root]
  nil)

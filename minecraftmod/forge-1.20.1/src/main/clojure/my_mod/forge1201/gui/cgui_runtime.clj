(ns my-mod.forge1201.gui.cgui-runtime
  "Forge 1.20.1 CGUI runtime: resize, render, and event dispatch for the pure Clojure
   widget tree. Used by the CGUI screen proxy to drive rendering and input."
  (:require
    [clojure.string :as str]
    [my-mod.gui.cgui :as cgui]
    [my-mod.platform.resource :as res]
    [my-mod.config.modid :as modid]
    [my-mod.util.log :as log])
  (:import
    (net.minecraft.client.gui GuiGraphics)
    (net.minecraft.client Minecraft)
    (net.minecraft.resources ResourceLocation)))

  (defonce ^:private texture-size-cache (atom {}))
  (defn- get-texture-size
    "Try to obtain the pixel size [width height] for the given `resource-location`.
     Caches results by resource-location string. Returns [w h] or nil if unknown.
     Uses best-effort reflective calls against Minecraft's TextureManager / texture
     objects so it is tolerant to different runtime implementations." 
    [resource-location]
    (when resource-location
      (let [k (str resource-location)
            cached (@texture-size-cache k)]
        (if cached
          cached
          (let [mc (Minecraft/getInstance)
                tm (try (.getTextureManager mc) (catch Exception _ nil))
                tex (try (when tm (.getTexture tm resource-location)) (catch Exception _ nil))
                ;; try multiple ways to obtain width/height
                size (try
                       (or
                         ;; common: texture object exposes getWidth/getHeight
                         (when tex
                           (try
                             (let [w (try (.getWidth tex) (catch Exception _ nil))
                                   h (try (.getHeight tex) (catch Exception _ nil))]
                               (when (and w h) [(int w) (int h)]))
                             (catch Exception _ nil)))
                         ;; some implementations wrap an image object
                         (when tex
                           (try
                             (let [img (try (.getTextureImage tex) (catch Exception _ nil))]
                               (when img
                                 (let [w (try (.getWidth img) (catch Exception _ nil))
                                       h (try (.getHeight img) (catch Exception _ nil))]
                                   (when (and w h) [(int w) (int h)]))))
                             (catch Exception _ nil)))
                         ;; as a last resort: try accessing fields reflectively
                         (when tex
                           (try
                             (let [cls (class tex)
                                   wf (try (.getDeclaredField cls "width") (catch Exception _ nil))
                                   hf (try (.getDeclaredField cls "height") (catch Exception _ nil))]
                               (when (and wf hf)
                                 (doto wf (.setAccessible true))
                                 (doto hf (.setAccessible true))
                                 (let [w (.getInt wf tex)
                                       h (.getInt hf tex)]
                                   (when (and (number? w) (number? h)) [(int w) (int h)]))))
                           (catch Exception _ nil))))
                       (catch Exception _ nil))]
            (when size (swap! texture-size-cache assoc k size))
            size)))))

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
                           :center (int (Math/round (/ (- pw w) 2.0)))
                           :right  (int (Math/round (- pw w)))
                           ;; default :left or nil
                           0)
          align-offset-y (case align-h
                           :center (int (Math/round (/ (- ph h) 2.0)))
                           :bottom (int (Math/round (- ph h)))
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
  "Set root widget size to match screen dimensions. Optional: center the root.
   Our GUIs use fixed layout with leftPos/topPos from the container screen, so we typically
   only set size so hit-testing and layout know the area."
  [root width height]
  (when root
    (cgui/set-size! root width height))
  root)
  
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
          (kind-matches? kind :drawtexture)
          (when-let [tex (ensure-resource-location (:texture state))]
            (let [uv (:uv state)
                  [u v uw uh] (if (and (sequential? uv) (>= (count uv) 4)) uv [0 0 (int w) (int h)])
                  fractional? (and (number? u) (number? v) (number? uw) (number? uh)
                                    (<= (double u) 1.0) (<= (double v) 1.0)
                                    (<= (double uw) 1.0) (<= (double uh) 1.0))
                  tex-size (when fractional? (get-texture-size tex))
                  basis-w (if (and tex-size (number? (first tex-size))) (first tex-size) w-int)
                  basis-h (if (and tex-size (number? (second tex-size))) (second tex-size) h-int)
                  u-px (if fractional? (int (Math/round (* (double u) (double basis-w)))) (int u))
                  v-px (if fractional? (int (Math/round (* (double v) (double basis-h)))) (int v))
                  uw-px (if fractional? (int (Math/round (* (double uw) (double basis-w)))) (int uw))
                  uh-px (if fractional? (int (Math/round (* (double uh) (double basis-h)))) (int uh))]
              (when (and uw-px uh-px)
                (.blit gg tex x y u-px v-px uw-px uh-px))))

          (kind-matches? kind :textbox)
          (let [text  (str (or (:text state) ""))
                color (unchecked-int (or (:color state) 0xFFFFFF))
                font  (.font (Minecraft/getInstance))]
            (when (seq text)
              (.drawString gg font text x y color))
            ;; caret rendering for editable textboxes when focused
            (when (and (:editable? state)
                       (some-> @(:metadata root) :focused?) )
              (try
                (let [font (.font (Minecraft/getInstance))
                      ;; compute caret x at end of text for now
                      caret-visible? (< (mod (System/currentTimeMillis) 1000) 500)
                      caret-x (+ x (int (.width font (str (or (:text state) "")))))]
                  (when caret-visible?
                    (.drawString gg font "|" caret-x y color)))
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
    (doseq [[widget [abs-x abs-y] scale] (collect-widgets-z-ordered root [0 0] 1.0 nil)]
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
              (if inside
                (let [children (cgui/get-widgets node)
                      child-stack (reduce (fn [acc child]
                                           (conj acc [child x0 y0]))
                                         rest-stack (reverse children))]
                  (recur child-stack node))
                (recur rest-stack best)))))))))

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
      (cgui/emit-widget-event! target :key {:keyCode keyCode :scanCode scanCode :typedChar typedChar}))))

(defn dispose!
  "Release any CGUI state. Currently a no-op; widget tree is GC'd when screen is closed."
  [_root]
  nil)

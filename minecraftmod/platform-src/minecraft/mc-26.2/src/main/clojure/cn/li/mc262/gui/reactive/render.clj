(ns cn.li.mc262.gui.reactive.render
  "Kind renderers for reactive UI on Minecraft 26.2."
  (:require [cn.li.mc262.client.texture-registry :as tex-registry]
            [cn.li.mc262.gui.cgui.font :as cgui-font]
            [cn.li.mc262.gui.reactive.clock :as clock]
            [cn.li.mcmod.ui.node :as node]
            [cn.li.mcmod.ui.layout :as ui-layout]
            [clojure.string :as str])
  (:import [cn.li.mc262.client GuiGraphicsHelper]
           [cn.li.mcmod.ui.node INode]
           [cn.li.mcmod.uipojo.runtime UiRt]
           [cn.li.mcver ResourceLocations]
           [net.minecraft.client Minecraft]
           [net.minecraft.client.gui GuiGraphicsExtractor]
           [net.minecraft.client.renderer.texture MissingTextureAtlasSprite]
           [net.minecraft.core.registries BuiltInRegistries]
           [net.minecraft.resources Identifier]
           [net.minecraft.world.item Item ItemStack Items]
           [net.minecraft.world.level.block Block Blocks]
           [org.joml Matrix3x2fStack]))

(declare draw-tape!)

(def ^:private SLOT-BOX-FILL      0)
(def ^:private SLOT-BOX-OUTLINE   1)
(def ^:private SLOT-BOX-OUTLINE-W 2)
(def ^:private SLOT-BOX-TINT      3)
(def ^:private SLOT-BOX-HOVER     4)

(def ^:private SLOT-IMG-SRC  0)
(def ^:private SLOT-IMG-BAKED-RL 2)
(def ^:private SLOT-IMG-ALPHA 0)
(def ^:private SLOT-IMG-U     1)
(def ^:private SLOT-IMG-V     2)
(def ^:private SLOT-IMG-TEX-W 3)
(def ^:private SLOT-IMG-TEX-H 4)

(def ^:private SLOT-TEXT-TEXT    0)
(def ^:private SLOT-TEXT-BAKED  8)

(def ^:private SLOT-PROG-PROGRESS 0)
(def ^:private SLOT-PROG-BANDS   11)

(def ^:private SLOT-NS-SRC 0)
(def ^:private SLOT-NS-LINE 1)
(def ^:private SLOT-NS-BAKED 2)
(def ^:private SLOT-NS-MARGIN 0)

(defn- resolve-tex-loc [tex-key]
  (cond
    (instance? Identifier tex-key) tex-key
    (keyword? tex-key) (tex-registry/resolve-texture tex-key)
    (string? tex-key) (ResourceLocations/parse tex-key)
    :else nil))

(defn- node-abs-x [^INode n] (.getAbsX n))
(defn- node-abs-y [^INode n] (.getAbsY n))
(defn- node-scale [^INode n] (.getCumScale n))
(defn- scaled-w [^INode n] (* (.getW n) (node-scale n)))
(defn- scaled-h [^INode n] (* (.getH n) (node-scale n)))

(defn- argb [^long a ^long r ^long g ^long b]
  (unchecked-int (bit-or (bit-shift-left a 24) (bit-shift-left r 16) (bit-shift-left g 8) b)))

(defn render-box! [^GuiGraphicsExtractor gg ^INode node]
  (let [x  (node-abs-x node)  y  (node-abs-y node)
        w  (scaled-w node)    h  (scaled-h node)
        ix (unchecked-int x)  iy (unchecked-int y)
        iw (unchecked-int (+ x w))  ih (unchecked-int (+ y h))]
    (let [fill-argb (unchecked-int (long (.getDSlot node SLOT-BOX-FILL)))]
      (when (not= fill-argb 0)
        (.fill gg ix iy iw ih fill-argb)))
    (let [outline-argb (unchecked-int (long (.getDSlot node SLOT-BOX-OUTLINE)))
          outline-w    (.getDSlot node SLOT-BOX-OUTLINE-W)]
      (when (and (not= outline-argb 0) (> outline-w 0.0))
        (.fill gg ix iy iw (unchecked-int (+ iy outline-w)) outline-argb)
        (.fill gg ix (unchecked-int (- ih outline-w)) iw ih outline-argb)
        (.fill gg ix iy (unchecked-int (+ ix outline-w)) ih outline-argb)
        (.fill gg (unchecked-int (- iw outline-w)) iy iw ih outline-argb)))
    (let [hovered? (.hasFlag node node/FLAG-HOVERED)
          raw (if hovered?
                (let [ht (.getDSlot node SLOT-BOX-HOVER)]
                  (if (> ht 0.0) ht (.getDSlot node SLOT-BOX-TINT)))
                (.getDSlot node SLOT-BOX-TINT))
          alpha (if (> raw 1.0)
                  (unchecked-int (bit-and (bit-shift-right (long raw) 24) 0xFF))
                  (unchecked-int (* 255.0 raw)))]
      (when (pos? alpha)
        (.fill gg ix iy iw ih (argb alpha 255 255 255))))))

(defn- resolve-rl
  ^Identifier [src]
  (when (and src (string? src) (not (str/blank? src)))
    (let [src (if (str/ends-with? src ".png") src (str src ".png"))]
      (try (ResourceLocations/parse src) (catch Exception _ nil)))))

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

(defonce ^:private ^java.util.HashSet seen-image-textures (java.util.HashSet.))

(defn- warn-missing-texture!
  [^INode node ^Identifier rl]
  (when (.add seen-image-textures rl)
    (try
      (let [tex (.getTexture (.getTextureManager (Minecraft/getInstance)) rl)
            missing-loc (MissingTextureAtlasSprite/getLocation)]
        (when (or (nil? tex)
                  (= (str rl) (str missing-loc)))
          (cn.li.mcmod.util.log/warn
            "UI image texture did not resolve: " (str rl)
            " (node " (str (.getId node)) ") — draws as a blank quad")))
      (catch Throwable _ nil))))

(defn render-image! [^GuiGraphicsExtractor gg ^INode node]
  (let [rl-obj (.getOSlot node SLOT-IMG-BAKED-RL)
        ^Identifier rl (if (instance? Identifier rl-obj)
                         rl-obj
                         (when-let [src (.getOSlot node SLOT-IMG-SRC)]
                           (when (string? src)
                             (let [resolved (resolve-rl src)]
                               (.setOSlot node SLOT-IMG-BAKED-RL resolved)
                               resolved))))]
    (when rl
      (warn-missing-texture! node rl)
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
            tex-h (if (pos? tex-h-raw) tex-h-raw 1.0)]
        (when (pos? alpha)
          (if (and (zero? u) (zero? v) (== tex-w 1.0) (== tex-h 1.0))
            (GuiGraphicsHelper/blit gg rl ix iy iw ih)
            (GuiGraphicsHelper/blitTexturedQuad gg rl
              (float x) (float y) (float (+ x w)) (float (+ y h)) 0.0
              (float u) (float (+ u tex-w))
              (float v) (float (+ v tex-h)))))))))

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
  (let [text (display-text node (or (.getOSlot node SLOT-TEXT-TEXT) ""))
        font-size-raw (.getDSlot node 0)
        font-size (if (pos? font-size-raw) font-size-raw 14.0)
        color-raw (.getOSlot node 1)
        raw-long (if (number? color-raw) (long color-raw) 0xFFFFFFFF)
        text-alpha (/ (double (bit-and (bit-shift-right raw-long 24) 0xFF)) 255.0)
        color (let [c (cgui-font/normalize-color-int raw-long)]
                (if (< text-alpha 1.0)
                  (unchecked-int (bit-or (bit-and (long c) 0x00FFFFFF)
                                         (bit-shift-left (long (* 255.0 text-alpha)) 24)))
                  c))
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

(defn render-text! [^GuiGraphicsExtractor gg ^INode node]
  (let [baked (.getOSlot node SLOT-TEXT-BAKED)]
    (when baked
      (let [{:keys [text color font-size font-desc align x-off y-off]} baked
            align-kw (or align :left)
            node-h (* (.getH node) (.getCumScale node))
            v-off (case (int (.getAlignH node))
                    1 (/ (- node-h (double font-size)) 2.0)
                    2 (- node-h (double font-size))
                    0.0)
            node-w (* (.getW node) (.getCumScale node))
            h-off (case align-kw :center (/ node-w 2.0) :right node-w 0.0)
            x (+ (node-abs-x node) x-off h-off)
            y (+ (node-abs-y node) y-off v-off)]
        (cgui-font/draw-text! gg (or font-desc {}) ^String text
                              x y font-size color align-kw true)))))

(defn bake-progress! [^INode node]
  (let [bg    (.getOSlot node 1)
        fg    (.getOSlot node 2)
        icon  (.getOSlot node 3)
        stops (.getOSlot node 0)]
    (when (string? bg)   (.setOSlot node 8  (resolve-rl bg)))
    (when (string? fg)   (.setOSlot node 9  (resolve-rl fg)))
    (when (string? icon) (.setOSlot node 10 (resolve-rl icon)))
    (when (vector? stops) (.setOSlot node SLOT-PROG-BANDS stops))))

(defn- icon-cutout [^INode node]
  (get (.getStaticProps node) :icon-cutout))

(defn- sample-progress-color [bands ^double t]
  (if (nil? bands)
    [255 255 255]
    (let [n (count bands)]
      (loop [i 0]
        (if (>= i n)
          (let [[_ r g b] (nth bands (dec n))] [(int r) (int g) (int b)])
          (let [[pos r g b] (nth bands i)
                pos (double pos)]
            (if (>= pos t)
              (if (zero? i)
                [(int r) (int g) (int b)]
                (let [[pos0 r0 g0 b0] (nth bands (dec i))
                      pos0 (double pos0)
                      span (- pos pos0)
                      f (if (zero? span) 0.0 (/ (- t pos0) span))]
                  [(int (+ (double r0) (* (- (double r) (double r0)) f)))
                   (int (+ (double g0) (* (- (double g) (double g0)) f)))
                   (int (+ (double b0) (* (- (double b) (double b0)) f)))]))
              (recur (inc i)))))))))

(defn render-progress! [^GuiGraphicsExtractor gg ^INode node]
  "Approximate progress with fill + optional texture blit (no ImmediateDraw trapezoid)."
  (let [x (node-abs-x node) y (node-abs-y node)
        w (scaled-w node) h (scaled-h node)
        percent (double (.getDSlot node SLOT-PROG-PROGRESS))
        remap (get (.getStaticProps node) :fill-remap)
        fill-pct (if remap
                   (+ (double (nth remap 0)) (* percent (double (nth remap 1))))
                   percent)
        filled-w (max 0 (int (* fill-pct w)))
        ix (unchecked-int x) iy (unchecked-int y)
        iw (unchecked-int w) ih (unchecked-int h)
        ^Identifier bg-rl (.getOSlot node 8)
        ^Identifier fg-rl (.getOSlot node 9)
        bands (.getOSlot node SLOT-PROG-BANDS)
        [cr cg cb] (sample-progress-color bands percent)]
    (when bg-rl
      (GuiGraphicsHelper/blit gg bg-rl ix iy iw ih))
    (when (and fg-rl (pos? filled-w))
      (GuiGraphicsHelper/blit gg fg-rl ix iy filled-w ih))
    (when (and (nil? fg-rl) (pos? filled-w))
      (.fill gg ix iy (+ ix filled-w) (+ iy ih) (argb 220 cr cg cb)))
    (when-let [cut (icon-cutout node)]
      (when-let [^Identifier icon-rl (.getOSlot node 10)]
        (let [[cx cy cw ch] cut]
          (GuiGraphicsHelper/blit gg icon-rl
                                  (unchecked-int (+ x (double cx)))
                                  (unchecked-int (+ y (double cy)))
                                  (unchecked-int (double cw))
                                  (unchecked-int (double ch))))))))

(defn render-shader-progress-node!
  "RenderPipeline-safe fallback for legacy shader widgets."
  [^GuiGraphicsExtractor gg ^INode node]
  (if (pos? (.getDSlot node SLOT-PROG-PROGRESS))
    (render-progress! gg node)
    (render-box! gg node)))

(def render-depth-mask! render-box!)
(def render-shader-quad! render-shader-progress-node!)
(def render-shader-progress! render-shader-progress-node!)
(def render-shader-ring! render-shader-progress-node!)

(defn- interpolate-color [c0 c1 ^double t]
  (let [[r0 g0 b0 a0] c0 [r1 g1 b1 a1] c1]
    [(+ r0 (* (- r1 r0) t))
     (+ g0 (* (- g1 g0) t))
     (+ b0 (* (- b1 b0) t))
     (+ a0 (* (- a1 a0) t))]))

(defn- gradient-color-at [stops ^double t]
  (let [n (count stops)]
    (loop [i 0]
      (if (>= i n)
        (nth stops (dec n))
        (let [[pos c] (nth stops i)
              pos (double pos)]
          (if (>= pos t)
            (if (zero? i)
              c
              (let [[pos0 c0] (nth stops (dec i))
                    span (- pos (double pos0))
                    f (if (zero? span) 0.0 (/ (- t (double pos0)) span))]
                (interpolate-color c0 c f)))
            (recur (inc i))))))))

(defn- bake-gradient-bands [stops]
  (when (seq stops) (vec stops)))

(defn bake-gradient! [^INode node]
  (when-let [stops (.getOSlot node 0)]
    (when (vector? stops)
      (.setOSlot node 1 (bake-gradient-bands stops)))))

(defn render-gradient! [^GuiGraphicsExtractor gg ^INode node]
  "Approximate horizontal/vertical gradient via fillGradient when two stops exist."
  (let [x (unchecked-int (node-abs-x node))
        y (unchecked-int (node-abs-y node))
        w (unchecked-int (scaled-w node))
        h (unchecked-int (scaled-h node))
        stops (or (.getOSlot node 1) (.getOSlot node 0))]
    (when (and (vector? stops) (>= (count stops) 2) (pos? w) (pos? h))
      (let [[_ c0] (first stops)
            [_ c1] (last stops)
            ->argb (fn [[r g b a]]
                     (argb (unchecked-int (* 255.0 (or a 1.0)))
                           (unchecked-int (or r 255))
                           (unchecked-int (or g 255))
                           (unchecked-int (or b 255))))]
        (.fillGradient gg x y (+ x w) (+ y h) (->argb c0) (->argb c1))))))

(defn render-line! [^GuiGraphicsExtractor gg ^INode node]
  (let [x1 (.getDSlot node 0) y1 (.getDSlot node 1)
        x2 (.getDSlot node 2) y2 (.getDSlot node 3)
        thick (max 1.0 (.getDSlot node 4))
        alpha (.getDSlot node 5)
        a (unchecked-int (* 255.0 (if (pos? alpha) alpha 1.0)))
        color (argb a 255 255 255)
        ix1 (unchecked-int (min x1 x2))
        iy1 (unchecked-int (min y1 y2))
        ix2 (unchecked-int (max x1 x2))
        iy2 (unchecked-int (max y1 y2))]
    (if (< (Math/abs (- x2 x1)) (Math/abs (- y2 y1)))
      (.fill gg (unchecked-int (- (/ (+ x1 x2) 2.0) (/ thick 2.0)))
             iy1
             (unchecked-int (+ (/ (+ x1 x2) 2.0) (/ thick 2.0)))
             iy2 color)
      (.fill gg ix1
             (unchecked-int (- (/ (+ y1 y2) 2.0) (/ thick 2.0)))
             ix2
             (unchecked-int (+ (/ (+ y1 y2) 2.0) (/ thick 2.0)))
             color))))

(defn bake-nine-slice! [^INode node]
  (let [src (.getOSlot node SLOT-NS-SRC)]
    (when (string? src)
      (.setOSlot node SLOT-NS-BAKED (resolve-rl src))))
  (let [line (.getOSlot node SLOT-NS-LINE)]
    (when (string? line)
      (.setOSlot node SLOT-NS-LINE (resolve-rl line)))))

(defn render-nine-slice! [^GuiGraphicsExtractor gg ^INode node]
  (when-let [^Identifier rl (.getOSlot node SLOT-NS-BAKED)]
    (let [x (unchecked-int (node-abs-x node))
          y (unchecked-int (node-abs-y node))
          w (unchecked-int (scaled-w node))
          h (unchecked-int (scaled-h node))
          m (max 1 (unchecked-int (.getDSlot node SLOT-NS-MARGIN)))]
      (GuiGraphicsHelper/blit9 gg rl x y w h 0 0 w h w h m m m m))))

(defn render-glow-line! [^GuiGraphicsExtractor gg ^INode node]
  (render-line! gg node))

(def ^:private SLOT-PREVIEW-ITEM-ID 0)
(def ^:private SLOT-PREVIEW-BAKED 2)
(def ^:private SLOT-P3D-TYPE 0)
(def ^:private SLOT-P3D-BLOCK 1)
(def ^:private SLOT-P3D-ITEM 2)
(def ^:private SLOT-P3D-BAKED 4)
(def ^:private SLOT-P3D-SCALE 1)

(defn- resolve-preview-stack
  ^ItemStack [id-value block?]
  (when (string? id-value)
    (try
      (let [id (ResourceLocations/parse id-value)]
        (if block?
          (let [^Block block (.getValue BuiltInRegistries/BLOCK id)]
            (when (and block (not= block Blocks/AIR))
              (ItemStack. (.asItem block))))
          (let [^Item item (.getValue BuiltInRegistries/ITEM id)]
            (when (and item (not= item Items/AIR))
              (ItemStack. item)))))
      (catch Throwable _ nil))))

(defn bake-preview-item! [^INode node]
  (when (nil? (.getOSlot node SLOT-PREVIEW-BAKED))
    (.setOSlot node SLOT-PREVIEW-BAKED
               (resolve-preview-stack (.getOSlot node SLOT-PREVIEW-ITEM-ID) false))))

(defn- render-stack!
  [^GuiGraphicsExtractor gg ^INode node ^ItemStack stack extra-scale]
  (let [x (node-abs-x node)
        y (node-abs-y node)
        w (scaled-w node)
        h (scaled-h node)
        scale (float (* (min (/ w 16.0) (/ h 16.0)) extra-scale))
        ^Matrix3x2fStack pose (.pose gg)]
    (.pushMatrix pose)
    (try
      (.translate pose (float (+ x (/ w 2.0))) (float (+ y (/ h 2.0))))
      (.scale pose scale scale)
      (.fakeItem gg stack -8 -8)
      (finally
        (.popMatrix pose)))))

(defn render-preview-item! [^GuiGraphicsExtractor gg ^INode node]
  (when-let [^ItemStack stack (.getOSlot node SLOT-PREVIEW-BAKED)]
    (render-stack! gg node stack 1.0)))

(defn bake-preview-3d! [^INode node]
  (when (nil? (.getOSlot node SLOT-P3D-BAKED))
    (let [block? (= :block (.getOSlot node SLOT-P3D-TYPE))
          id-value (.getOSlot node (if block? SLOT-P3D-BLOCK SLOT-P3D-ITEM))]
      (.setOSlot node SLOT-P3D-BAKED (resolve-preview-stack id-value block?)))))

(defn render-preview-3d! [^GuiGraphicsExtractor gg ^INode node]
  (when-let [^ItemStack stack (.getOSlot node SLOT-P3D-BAKED)]
    (let [requested-scale (.getDSlot node SLOT-P3D-SCALE)]
      (render-stack! gg node stack (if (pos? requested-scale) requested-scale 1.0)))))

(def ^:private crosshair-ring-unit-vecs
  (mapv (fn [idx]
          (let [a (/ (* 2.0 Math/PI idx) 24.0)]
            [(Math/cos a) (Math/sin a)]))
        (range 24)))

(defn render-crosshair! [^GuiGraphicsExtractor gg ^INode node]
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

(defn render-embedded-runtime!
  [^GuiGraphicsExtractor gg ^UiRt rt left top w h partial-ticks]
  (when (and rt (not (cn.li.mcmod.ui.runtime/disposed? rt)))
    (clock/tick! rt partial-ticks)
    (cn.li.mcmod.ui.runtime/resize! rt (double w) (double h))
    (cn.li.mcmod.ui.runtime/flush! rt)
    (ui-layout/ensure-layout! rt)
    (ui-layout/ensure-tape! rt)
    (draw-tape! gg rt (int left) (int top))))

(def kind-renderers
  {:box             {:render! render-box!             :bake! nil}
   :image           {:render! render-image!           :bake! bake-image!}
   :text            {:render! render-text!            :bake! bake-text!}
   :progress        {:render! render-progress!        :bake! bake-progress!}
   :depth-mask      {:render! render-depth-mask!      :bake! nil}
   :shader-quad     {:render! render-shader-quad!     :bake! nil}
   :shader-ring     {:render! render-shader-ring!     :bake! nil}
   :shader-progress {:render! render-shader-progress! :bake! nil}
   :gradient        {:render! render-gradient!        :bake! bake-gradient!}
   :line            {:render! render-line!            :bake! nil}
   :group           {:render! nil                     :bake! nil}
   :list            {:render! nil                     :bake! nil}
   :nine-slice      {:render! render-nine-slice!      :bake! bake-nine-slice!}
   :glow-line       {:render! render-glow-line!       :bake! nil}
   :preview-item    {:render! render-preview-item!    :bake! bake-preview-item!}
   :preview-3d      {:render! render-preview-3d!      :bake! bake-preview-3d!}
   :crosshair       {:render! render-crosshair!       :bake! nil}})

(defn draw-tape!
  "Render the flat tape via GuiGraphicsExtractor + Matrix3x2fStack."
  [^GuiGraphicsExtractor gg ^UiRt rt left top]
  (let [^objects tape (cn.li.mcmod.ui.runtime/get-tape-arr rt)
        n (alength tape)
        push-clip ui-layout/push-clip-sentinel
        pop-clip  ui-layout/pop-clip-sentinel
        push-xf   ui-layout/push-transform-sentinel
        pop-xf    ui-layout/pop-transform-sentinel]
    (when (pos? n)
      (let [^Matrix3x2fStack pose (.pose gg)]
        (.pushMatrix pose)
        (.translate pose (float left) (float top))
        (loop [i 0]
          (when (< i n)
            (let [entry (aget tape i)]
              (if (instance? INode entry)
                (let [^INode nd entry
                      kdef (get kind-renderers (.getKind nd))]
                  (when (.hasFlag nd node/FLAG-RENDER-DIRTY)
                    (when-let [bake-fn (:bake! kdef)]
                      (bake-fn nd))
                    (.clearFlag nd node/FLAG-RENDER-DIRTY))
                  (when-let [render-fn (:render! kdef)]
                    (render-fn gg nd)))
                (cond (identical? push-clip entry) (.pushMatrix pose)
                      (identical? pop-clip entry)  (.popMatrix pose)
                      (identical? push-xf entry)   (.pushMatrix pose)
                      (identical? pop-xf entry)    (.popMatrix pose))))
            (recur (unchecked-inc-int i))))
        (.popMatrix pose)))))

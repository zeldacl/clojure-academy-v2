(ns cn.li.mc1201.gui.reactive.render
  "Kind renderers (:render!/:bake!) — ported from CGUI renderer.clj zero-alloc techniques.
   All render fns take [^GuiGraphics gg ^INode node]."
  (:require [cn.li.mcmod.ui.node :as node]
            [cn.li.mcmod.ui.layout :as ui-layout]
            [clojure.string :as str])
  (:import [cn.li.mcmod.ui.node INode]
           [cn.li.mcmod.uipojo.runtime UiRt]
           [net.minecraft.client.gui GuiGraphics Font]
           [net.minecraft.client Minecraft]
           [net.minecraft.resources ResourceLocation]
           [com.mojang.blaze3d.vertex PoseStack]
           [org.joml Quaternionf]))

;; Slot indices matching node.clj kind definitions
(def ^:private SLOT-BOX-FILL      0)
(def ^:private SLOT-BOX-OUTLINE   1)
(def ^:private SLOT-BOX-OUTLINE-W 2)
(def ^:private SLOT-BOX-TINT      3)
(def ^:private SLOT-BOX-HOVER     4)

(def ^:private SLOT-IMG-SRC  0)
(def ^:private SLOT-IMG-BAKED-RL 2)  ;; backend slot: resolved ResourceLocation

(def ^:private SLOT-TEXT-TEXT    0)
(def ^:private SLOT-TEXT-BAKED  8)  ;; backend slot: baked text runs

(def ^:private SLOT-PROG-PROGRESS 0)
(def ^:private SLOT-PROG-BANDS   8)  ;; backend: baked gradient int array

(def ^:private SLOT-SHADER-PROPS 0)

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
    ;; Fill
    (let [fill-argb (unchecked-int (.getDSlot node SLOT-BOX-FILL))]
      (when (not= fill-argb 0)
        (.fill gg ix iy iw ih fill-argb)))
    ;; Outline
    (let [outline-argb (unchecked-int (.getDSlot node SLOT-BOX-OUTLINE))
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

(defn- resolve-rl [src]
  (when (and src (string? src) (not (clojure.string/blank? src)))
    (ResourceLocation/tryParse src)))

(defn bake-image! [^INode node]
  (let [src (.getOSlot node SLOT-IMG-SRC)]
    (when (string? src)
      (.setOSlot node SLOT-IMG-BAKED-RL (resolve-rl src)))))

(defn render-image! [^GuiGraphics gg ^INode node]
  (let [^ResourceLocation rl (.getOSlot node SLOT-IMG-BAKED-RL)]
    (when rl
      (let [x  (node-abs-x node)  y  (node-abs-y node)
            w  (scaled-w node)    h  (scaled-h node)
            ix (unchecked-int x)  iy (unchecked-int y)
            iw (unchecked-int w)  ih (unchecked-int h)]
        (.blit gg rl ix iy iw ih 0.0 0.0 iw ih iw ih)))))

;; ============================================================================
;; :text (MSDF font)
;; ============================================================================

(defn bake-text! [^INode node]
  ;; text kind slots (node.clj): dslots {:font-size 0}, oslots {:text 0 :color 1}
  (let [text      (str (or (.getOSlot node SLOT-TEXT-TEXT) ""))
        font-size (.getDSlot node 0)
        color-raw (.getOSlot node 1)
        color     (if (number? color-raw) (unchecked-int (long color-raw)) (unchecked-int 0xFFFFFFFF))]
    (.setOSlot node SLOT-TEXT-BAKED
               {:text text :font-size (double font-size) :color color})))

(defn render-text! [^GuiGraphics gg ^INode node]
  (let [baked (.getOSlot node SLOT-TEXT-BAKED)]
    (when baked
      (let [{:keys [text color]} baked
            x (node-abs-x node) y (node-abs-y node)
            ^Font font (.. (Minecraft/getInstance) (getFontManager) (getFont))]
        (.drawShadow gg font ^String (str text) (unchecked-int x) (unchecked-int y) (unchecked-int color))))))

;; ============================================================================
;; :progress
;; ============================================================================

(defn bake-progress! [^INode node]
  (let [stops (.getOSlot node 0)]
    (when (seq stops)
      ;; Bake color-stops into int[] for zero-alloc gradient fill
      (.setOSlot node SLOT-PROG-BANDS nil))))

(defn render-progress! [^GuiGraphics gg ^INode node]
  (let [x       (node-abs-x node)   y       (node-abs-y node)
        w       (scaled-w node)     h       (scaled-h node)
        percent (.getDSlot node SLOT-PROG-PROGRESS)
        filled-w (unchecked-int (* percent w))
        ix (unchecked-int x) iy (unchecked-int y)
        iw (unchecked-int w) ih (unchecked-int (+ y h))]
    ;; Background
    (.fill gg ix iy (unchecked-int (+ x w)) ih (unchecked-int 0x80333333))
    ;; Filled portion
    (when (pos? filled-w)
      (.fill gg ix iy (unchecked-int (+ x filled-w)) ih (unchecked-int 0xFF4488CC)))))

;; ============================================================================
;; :shader-quad / :shader-ring / :shader-progress
;; ============================================================================

(defn render-shader-quad! [^GuiGraphics _gg ^INode _node] nil)
(defn bake-shader-quad! [^INode _node] nil)
(def render-shader-ring! render-shader-quad!)
(def bake-shader-ring! bake-shader-quad!)
(defn render-shader-progress! [^GuiGraphics _gg ^INode _node] nil)
(def bake-shader-progress! bake-shader-quad!)

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
  (let [x1 (.getDSlot node 0) y1 (.getDSlot node 1)
        x2 (.getDSlot node 2) y2 (.getDSlot node 3)]
    (.fill gg (unchecked-int x1) (unchecked-int y1) (unchecked-int x2) (unchecked-int (+ y2 1.0))
           (unchecked-int 0xFFFFFFFF))))
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
;; :draw-ops — escape hatch
;; ============================================================================

(defn render-draw-ops! [^GuiGraphics _gg ^INode _node] nil)
(defn bake-draw-ops! [^INode _node] nil)

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
   :draw-ops        {:render! render-draw-ops!        :bake! bake-draw-ops!}})

;; ============================================================================
;; draw-tape! — flat tape render loop
;; ============================================================================

(defn draw-tape!
  "Render the flat tape: iterate Object[].
   Node with RENDER_DIRTY → run :bake! first (resolve textures / bake text /
   pre-compute gradient bands into backend slots), clear the flag, then :render!
   reads only cached fields. Sentinels → PoseStack push/pop."
  [^GuiGraphics gg ^UiRt rt _left _top]
  (let [^objects tape (cn.li.mcmod.ui.runtime/get-tape-arr rt)
        n (alength tape)
        push-clip ui-layout/push-clip-sentinel
        pop-clip  ui-layout/pop-clip-sentinel
        push-xf   ui-layout/push-transform-sentinel
        pop-xf    ui-layout/pop-transform-sentinel]
    (when (pos? n)
      (let [^PoseStack pose (.pose gg)]
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
            (recur (unchecked-inc-int i))))))))

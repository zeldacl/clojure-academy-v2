(ns cn.li.mc262.presentation.backend
  "Minecraft 26.2 Presentation backend seam.

   The profile advertises streaming/UBO/instancing capabilities. The concrete
   mapped upload path is the only version-specific implementation point; it
   must preserve the neutral Render IR ordering and stage semantics.

   UI draw commands arrive pre-sorted and run-batched as a UiDrawList (see
   PaintKernel/CmdBuf in presentation-core): every command in one run shares
   an (op, resource, clip), so an Identifier is resolved and the scissor is
   set at most once per run rather than once per command. Only RECT/IMAGE/
   TEXT/NINE/ITEM/MODEL ever actually reach a run here - PROGRESS/GRADIENT/
   COMPOSITE are already decomposed into those by PaintKernel before this
   code ever sees them. Unlike the two older targets, blitTintedQuad takes
   its color directly rather than through a RenderSystem/setShaderColor
   global, so there is no shader-color state to restore between runs."
  (:require [cn.li.mcmod.runtime.presentation-backend :as neutral]
            [cn.li.mcmod.runtime.presentation-bridge :as presentation-bridge]
            [cn.li.mc262.gui.cgui.font :as cgui-font])
  (:import [cn.li.mcmod.runtime FramePacket RenderCommand RenderCommand$Batch
            RenderCommand$AudioContribution RenderCommand$Beam RenderCommand$Billboard
            RenderCommand$CameraContribution
            RenderCommand$Layer RenderCommand$Mesh RenderCommand$OrderBarrier
            RenderCommand$ParticleBatch RenderCommand$PostProcess
            RenderCommand$Ribbon RenderPass
            UiResourceRef]
           [cn.li.mcmod.runtime.ui UiDrawList UiOp]
           [cn.li.mc262.client GuiGraphicsHelper]
           [cn.li.mcver ResourceLocations]
           [net.minecraft.client Minecraft]
           [net.minecraft.client.gui GuiGraphicsExtractor Font]
           [net.minecraft.resources Identifier]))

(def profile :mc-26-2)

(defn submit! [backend stage frame-packet & [render-context]]
  ((:submit! backend) stage frame-packet render-context))

(defn reload-resources! [backend generation]
  (neutral/reload-resources! backend generation))

(defn- callback! [context key values]
  (when (map? context)
    (when-let [f (get context key)]
      (when (fn? f) (apply f values)))))

;; ============================== UI run drawing ==============================

(defn- draw-rect-run! [^GuiGraphicsExtractor gg ^UiDrawList dl start end]
  (let [^floats geom (.geom dl) ^ints rgba (.rgba dl)]
    (loop [i (int start)]
      (when (< i (int end))
        (let [g (* i 4)]
          (.fill gg (int (aget geom g)) (int (aget geom (unchecked-inc-int g)))
                 (int (+ (aget geom g) (aget geom (+ g 2))))
                 (int (+ (aget geom (unchecked-inc-int g)) (aget geom (+ g 3))))
                 (aget rgba i)))
        (recur (unchecked-inc-int i))))))

(defn- draw-image-run! [^GuiGraphicsExtractor gg ^UiDrawList dl start end ^Identifier rl]
  (let [^floats geom (.geom dl) ^ints rgba (.rgba dl)]
    (loop [i (int start)]
      (when (< i (int end))
        (let [g (* i 4)
              x0 (int (aget geom g)) y0 (int (aget geom (unchecked-inc-int g)))
              x1 (int (+ (aget geom g) (aget geom (+ g 2))))
              y1 (int (+ (aget geom (unchecked-inc-int g)) (aget geom (+ g 3))))]
          (GuiGraphicsHelper/blitTintedQuad gg rl x0 y0 x1 y1 0.0 1.0 0.0 1.0 (aget rgba i)))
        (recur (unchecked-inc-int i))))))

(defn- blit-nine-patch!
  "AcademyCraft BlendQuad / main TechUI :nine-slice (see mc1201 backend)."
  [^GuiGraphicsExtractor gg ^Identifier rl
   x y w h margin _tex-size argb]
  (let [m (max 1.0 (double margin))
        x0 (- (double x) m)  x1 (double x)  x2 (+ (double x) (double w))  x3 (+ (double x) (double w) m)
        y0 (- (double y) m)  y1 (double y)  y2 (+ (double y) (double h))  y3 (+ (double y) (double h) m)
        d-xs [x0 x1 x2 x3]
        d-ys [y0 y1 y2 y3]
        step (/ 1.0 3.0)
        ;; black @ 0.5 alpha, matching Colors.monoBlend(0, 0.5)
        tint (unchecked-int 0x80000000)]
    (dotimes [i 3]
      (dotimes [j 3]
        (let [u0 (* i step) u1 (+ u0 step)
              v0 (* j step) v1 (+ v0 step)
              xa (nth d-xs i) xb (nth d-xs (inc i))
              ya (nth d-ys j) yb (nth d-ys (inc j))]
          (when (and (> (- xb xa) 0.5) (> (- yb ya) 0.5))
            (GuiGraphicsHelper/blitTintedQuad gg rl
                                              (int xa) (int ya) (int xb) (int yb)
                                              u0 u1 v0 v1 tint)))))))

(defn- draw-nine-run! [^GuiGraphicsExtractor gg ^UiDrawList dl start end ^Identifier rl]
  (let [^floats geom (.geom dl) ^ints rgba (.rgba dl) ^floats scalar (.scalar dl)]
    (loop [i (int start)]
      (when (< i (int end))
        (let [g (* i 4)
              x (aget geom g) y (aget geom (unchecked-inc-int g))
              w (aget geom (+ g 2)) h (aget geom (+ g 3))
              margin (max 1.0 (double (aget scalar i)))
              argb (aget rgba i)]
          (blit-nine-patch! gg rl x y w h margin 0.0 argb))
        (recur (unchecked-inc-int i))))))

(defn- draw-text-run! [^GuiGraphicsExtractor gg ^UiDrawList dl start end]
  (let [^floats geom (.geom dl) ^ints rgba (.rgba dl) ^floats scalar (.scalar dl) ^objects aux (.aux dl)]
    (loop [i (int start)]
      (when (< i (int end))
        (let [g (* i 4)]
          (cgui-font/draw-text! gg nil (str (aget aux i))
                                (aget geom g) (aget geom (unchecked-inc-int g))
                                (aget scalar i) (aget rgba i) :left true))
        (recur (unchecked-inc-int i))))))

(defn- draw-item-run! [^GuiGraphicsExtractor gg context stage ^UiDrawList dl start end]
  (let [^floats geom (.geom dl) ^objects aux (.aux dl)]
    (loop [i (int start)]
      (when (< i (int end))
        (let [g (* i 4)]
          (callback! context :draw-ui-item-preview!
                     [gg stage (aget aux i) (aget geom g) (aget geom (unchecked-inc-int g)) 1.0]))
        (recur (unchecked-inc-int i))))))

(defn- draw-model-run! [^GuiGraphicsExtractor gg context stage ^UiDrawList dl start end]
  (let [^floats geom (.geom dl) ^objects aux (.aux dl)]
    (loop [i (int start)]
      (when (< i (int end))
        (let [g (* i 4)]
          (callback! context :draw-ui-model-preview!
                     [gg stage (aget aux i) (aget geom g) (aget geom (unchecked-inc-int g))
                      (aget geom (+ g 2)) (aget geom (+ g 3))]))
        (recur (unchecked-inc-int i))))))

(defn- resource-location ^Identifier [^UiResourceRef ref]
  (ResourceLocations/of (.namespace ref) (.path ref)))

(defn- run-resource
  "IMAGE/NINE runs may carry res=-1 when the view has no bound texture."
  ^UiResourceRef [^objects resources ^ints run-res r]
  (let [idx (int (aget run-res r))]
    (when (and (>= idx 0) (< idx (alength resources)))
      (let [ref (aget resources idx)]
        (when (instance? UiResourceRef ref) ref)))))

(defn- apply-scissor-clip!
  [^GuiGraphicsExtractor gg ^floats clip-rects cur-clip clip]
  (when (not= clip cur-clip)
    (when (>= cur-clip 0)
      (.disableScissor gg))
    (when (>= clip 0)
      (let [b (* clip 4)]
        (.enableScissor gg (int (aget clip-rects b)) (int (aget clip-rects (unchecked-inc-int b)))
                        (int (+ (aget clip-rects b) (aget clip-rects (+ b 2))))
                        (int (+ (aget clip-rects (unchecked-inc-int b)) (aget clip-rects (+ b 3))))))))
  clip)

(defn- draw-ui-draw-list! [^GuiGraphicsExtractor gg context stage ^UiDrawList dl]
  (let [^ints run-op (.runOp dl) ^ints run-res (.runRes dl) ^ints run-clip (.runClip dl)
        ^ints run-start (.runStart dl) ^ints run-end (.runEnd dl)
        ^floats clip-rects (.clipRects dl)
        ^objects resources (.resources dl)
        n (.runCount dl)
        final-clip
        (loop [r (int 0) cur-clip (int -1)]
          (if (< r n)
            (let [clip (aget run-clip r)
                  cur-clip' (apply-scissor-clip! gg clip-rects cur-clip clip)
                  s (aget run-start r) e (aget run-end r) op (aget run-op r)]
              (cond
                (= op UiOp/RECT) (draw-rect-run! gg dl s e)
                (= op UiOp/IMAGE)
                (when-let [ref (run-resource resources run-res r)]
                  (draw-image-run! gg dl s e (resource-location ref)))
                (= op UiOp/NINE)
                (when-let [ref (run-resource resources run-res r)]
                  (draw-nine-run! gg dl s e (resource-location ref)))
                (= op UiOp/TEXT) (draw-text-run! gg dl s e)
                (= op UiOp/ITEM) (draw-item-run! gg context stage dl s e)
                (= op UiOp/MODEL) (draw-model-run! gg context stage dl s e)
                :else nil)
              (recur (unchecked-inc-int r) cur-clip'))
            cur-clip))]
    (when (>= final-clip 0)
      (.disableScissor gg))))

;; ============================== world/VFX commands ==============================

(defn- draw-command! [^GuiGraphicsExtractor graphics stage context ^RenderCommand command]
  (condp instance? command
    RenderCommand$AudioContribution
    (let [^RenderCommand$AudioContribution c command]
      (callback! context :play-audio! [stage (.soundId c) (.volume c) (.pitch c)]))

    RenderCommand$Layer
    (let [^RenderCommand$Layer c command]
      (callback! context :set-layer! [graphics stage (.id c)]))

    RenderCommand$Mesh
    (let [^RenderCommand$Mesh c command]
      (callback! context :draw-mesh!
                 [graphics stage (.meshId c) (.materialId c) (.instanceCount c) (.payload c)]))

    RenderCommand$Billboard
    (let [^RenderCommand$Billboard c command]
      (callback! context :draw-billboard!
                 [graphics stage (.textureId c) (.materialId c) (.instanceCount c)
                  (.originX c) (.originY c) (.originZ c)]))

    RenderCommand$ParticleBatch
    (let [^RenderCommand$ParticleBatch c command]
      (callback! context :draw-particle-batch!
                 [graphics stage (.materialId c) (.count c) (.originX c) (.originY c) (.originZ c)]))

    RenderCommand$Ribbon
    (let [^RenderCommand$Ribbon c command]
      (callback! context :draw-ribbon! [graphics stage (.materialId c) (.pointCount c)]))

    RenderCommand$Beam
    (let [^RenderCommand$Beam c command]
      (callback! context :draw-beam! [graphics stage (.materialId c) (.segmentCount c)]))

    RenderCommand$CameraContribution
    (let [^RenderCommand$CameraContribution c command]
      (callback! context :apply-camera! [stage (.fovDelta c) (.shakeX c) (.shakeY c) (.roll c)]))

    RenderCommand$PostProcess
    (let [^RenderCommand$PostProcess c command]
      (callback! context :apply-post-process! [graphics stage (.materialId c) (.intensity c)]))

    RenderCommand$OrderBarrier (callback! context :order-barrier! [graphics stage])

    RenderCommand$Batch
    (let [^RenderCommand$Batch c command]
      (callback! context :draw-batch!
                 [graphics stage (.primitive c) (.material c) (.variant c) (.count c) (.payload c)]))

    nil))

(defn render! [graphics stage ^FramePacket frame]
  (let [context (if (map? graphics) graphics {})
        ^GuiGraphicsExtractor gg (if (map? graphics) (:graphics graphics) graphics)
        wanted (neutral/stage->render-stage stage)
        ^UiDrawList dl (.uiFor frame wanted)]
    (when (and dl (pos? (.count dl)) (instance? GuiGraphicsExtractor gg))
      (draw-ui-draw-list! gg context stage dl))
    (doseq [^RenderPass pass (.passes frame)
            :when (= wanted (.stage pass))
            ^RenderCommand command (.commands pass)]
      (when (or (instance? GuiGraphicsExtractor gg)
                (and (map? context) (some fn? (vals context))))
        (draw-command! gg stage context command))))
  frame)

(defn create []
  (presentation-bridge/install-text-metrics! (cgui-font/text-metrics))
  (neutral/install-renderer! (neutral/create profile) render!))

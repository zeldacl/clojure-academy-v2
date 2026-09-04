(ns cn.li.mc1201.presentation.backend
  "Minecraft 1.20.1 Presentation backend seam.

   The backend deliberately consumes the neutral mcmod frame envelope. Mapping
   to BufferSource/GuiGraphics belongs in the version-owned render callback;
   no UI or effect policy is allowed here.

   UI draw commands arrive pre-sorted and run-batched as a UiDrawList (see
   PaintKernel/CmdBuf in presentation-core): every command in one run shares
   an (op, resource, clip), so a ResourceLocation is resolved and the
   scissor is set at most once per run rather than once per command. The
   only two draw opcodes that ever actually reach a run here are RECT/
   IMAGE/TEXT/NINE/ITEM/MODEL - PROGRESS/GRADIENT/COMPOSITE are already
   decomposed into those by PaintKernel before this code ever sees them."
  (:require [cn.li.mcmod.runtime.presentation-backend :as neutral]
            [cn.li.mcmod.runtime.presentation-bridge :as presentation-bridge]
            [cn.li.mc1201.gui.cgui.font :as cgui-font])
  (:import [cn.li.mcmod.runtime FramePacket RenderCommand RenderCommand$Batch
            RenderCommand$AudioContribution RenderCommand$Beam RenderCommand$Billboard
            RenderCommand$CameraContribution
            RenderCommand$Layer RenderCommand$Mesh RenderCommand$OrderBarrier
            RenderCommand$ParticleBatch RenderCommand$PostProcess
            RenderCommand$Ribbon RenderPass
            UiResourceRef]
           [cn.li.mcmod.runtime.ui UiDrawList UiOp]
           [cn.li.mc1201.client GuiGraphicsHelper]
           [com.mojang.blaze3d.systems RenderSystem]
           [net.minecraft.client Minecraft]
           [net.minecraft.client.gui GuiGraphics Font]
           [net.minecraft.resources ResourceLocation]))

(def profile :mc-1-20-1)

(defn submit! [backend stage frame-packet & [render-context]]
  ((:submit! backend) stage frame-packet render-context))

(defn reload-resources! [backend generation]
  (neutral/reload-resources! backend generation))

(defn- callback! [context key values]
  (when (map? context)
    (when-let [f (get context key)]
      (when (fn? f)
        (apply f values)))))

(defn- rgba-components [^long rgba]
  (let [a (float (/ (bit-and (unsigned-bit-shift-right rgba 24) 0xff) 255.0))
        r (float (/ (bit-and (unsigned-bit-shift-right rgba 16) 0xff) 255.0))
        g (float (/ (bit-and (unsigned-bit-shift-right rgba 8) 0xff) 255.0))
        b (float (/ (bit-and rgba 0xff) 255.0))]
    [r g b a]))

;; ============================== UI run drawing ==============================

(defn- draw-rect-run! [^GuiGraphics gg ^UiDrawList dl start end]
  (let [^floats geom (.geom dl) ^ints rgba (.rgba dl)]
    (loop [i (int start)]
      (when (< i (int end))
        (let [g (* i 4)]
          (.fill gg (int (aget geom g)) (int (aget geom (unchecked-inc-int g)))
                 (int (+ (aget geom g) (aget geom (+ g 2))))
                 (int (+ (aget geom (unchecked-inc-int g)) (aget geom (+ g 3))))
                 (aget rgba i)))
        (recur (unchecked-inc-int i))))))

(defn- draw-image-run! [^GuiGraphics gg ^UiDrawList dl start end ^ResourceLocation rl]
  ;; Flush pending fill/text batches once per run (not once per image, as
  ;; the pre-rewrite paint.clj always emitted exactly one image per batch
  ;; and so paid this cost per image) so order is preserved before ImmediateDraw.
  (.flush gg)
  (let [^floats geom (.geom dl) ^ints rgba (.rgba dl)]
    (loop [i (int start)]
      (when (< i (int end))
        (let [g (* i 4)
              x (aget geom g) y (aget geom (unchecked-inc-int g))
              x2 (+ x (aget geom (+ g 2))) y2 (+ y (aget geom (+ g 3)))
              [r gc b a] (rgba-components (long (aget rgba i)))]
          (RenderSystem/setShaderColor r gc b a)
          (GuiGraphicsHelper/blitTexturedQuad gg rl x y x2 y2 0.0 0.0 1.0 0.0 1.0)
          (RenderSystem/setShaderColor 1.0 1.0 1.0 1.0))
        (recur (unchecked-inc-int i))))))

(defn- blit-nine-patch!
  "Draw a 9-slice: corners keep texture px; edges/center stretch.
   `border` and `tex-size` are in texture pixels (main BlendQuad: 4 of 48)."
  [^GuiGraphics gg ^ResourceLocation rl
   x y w h border tex-size r g b a]
  (let [b (min (double border) (* 0.5 (double w)) (* 0.5 (double h)))
        tex (max 1.0 (double tex-size))
        ub (/ b tex)
        x0 (double x) x3 (+ x0 (double w))
        y0 (double y) y3 (+ y0 (double h))
        x1 (+ x0 b) x2 (- x3 b)
        y1 (+ y0 b) y2 (- y3 b)
        u0 0.0 u1 ub u2 (- 1.0 ub) u3 1.0
        v0 0.0 v1 ub v2 (- 1.0 ub) v3 1.0]
    (RenderSystem/setShaderColor (float r) (float g) (float b) (float a))
    (doseq [[xa xb ya yb ua ub' va vb']
            [[x0 x1 y0 y1 u0 u1 v0 v1]
             [x1 x2 y0 y1 u1 u2 v0 v1]
             [x2 x3 y0 y1 u2 u3 v0 v1]
             [x0 x1 y1 y2 u0 u1 v1 v2]
             [x1 x2 y1 y2 u1 u2 v1 v2]
             [x2 x3 y1 y2 u2 u3 v1 v2]
             [x0 x1 y2 y3 u0 u1 v2 v3]
             [x1 x2 y2 y3 u1 u2 v2 v3]
             [x2 x3 y2 y3 u2 u3 v2 v3]]]
      (when (and (> (- xb xa) 0.01) (> (- yb ya) 0.01))
        (GuiGraphicsHelper/blitTexturedQuad gg rl xa ya xb yb 0.0 ua ub' va vb')))
    (RenderSystem/setShaderColor 1.0 1.0 1.0 1.0)))

(defn- draw-nine-run! [^GuiGraphics gg ^UiDrawList dl start end ^ResourceLocation rl]
  (.flush gg)
  (let [^floats geom (.geom dl) ^ints rgba (.rgba dl)
        ^floats scalar (.scalar dl) ^objects aux (.aux dl)]
    (loop [i (int start)]
      (when (< i (int end))
        (let [g (* i 4)
              x (aget geom g) y (aget geom (unchecked-inc-int g))
              w (aget geom (+ g 2)) h (aget geom (+ g 3))
              border (double (aget scalar i))
              tex (double (or (aget aux i) 48.0))
              [r gc b a] (rgba-components (long (aget rgba i)))]
          (if (<= border 0.0)
            (do (RenderSystem/setShaderColor r gc b a)
                (GuiGraphicsHelper/blitTexturedQuad gg rl x y (+ x w) (+ y h)
                                                    0.0 0.0 1.0 0.0 1.0)
                (RenderSystem/setShaderColor 1.0 1.0 1.0 1.0))
            (blit-nine-patch! gg rl x y w h border tex r gc b a)))
        (recur (unchecked-inc-int i))))))

(defn- draw-text-run! [^GuiGraphics gg ^UiDrawList dl start end]
  (let [^floats geom (.geom dl) ^ints rgba (.rgba dl) ^floats scalar (.scalar dl) ^objects aux (.aux dl)]
    (loop [i (int start)]
      (when (< i (int end))
        (let [g (* i 4)]
          (cgui-font/draw-text! gg nil (str (aget aux i))
                                (aget geom g) (aget geom (unchecked-inc-int g))
                                (aget scalar i) (aget rgba i) :left true))
        (recur (unchecked-inc-int i))))))

(defn- draw-item-run! [^GuiGraphics gg context stage ^UiDrawList dl start end]
  (let [^floats geom (.geom dl) ^objects aux (.aux dl)]
    (loop [i (int start)]
      (when (< i (int end))
        (let [g (* i 4)]
          (callback! context :draw-ui-item-preview!
                     [gg stage (aget aux i) (aget geom g) (aget geom (unchecked-inc-int g)) 1.0]))
        (recur (unchecked-inc-int i))))))

(defn- draw-model-run! [^GuiGraphics gg context stage ^UiDrawList dl start end]
  (let [^floats geom (.geom dl) ^objects aux (.aux dl)]
    (loop [i (int start)]
      (when (< i (int end))
        (let [g (* i 4)]
          (callback! context :draw-ui-model-preview!
                     [gg stage (aget aux i) (aget geom g) (aget geom (unchecked-inc-int g))
                      (aget geom (+ g 2)) (aget geom (+ g 3))]))
        (recur (unchecked-inc-int i))))))

(defn- resource-location ^ResourceLocation [^UiResourceRef ref]
  (ResourceLocation. (.namespace ref) (.path ref)))

(defn- run-resource
  "IMAGE/NINE runs may carry res=-1 when the view has no bound texture."
  ^UiResourceRef [^objects resources ^ints run-res r]
  (let [idx (int (aget run-res r))]
    (when (and (>= idx 0) (< idx (alength resources)))
      (let [ref (aget resources idx)]
        (when (instance? UiResourceRef ref) ref)))))

(defn- apply-scissor-clip!
  "Transition GuiGraphics scissor state for one run-batch clip index.

  Clip indices < 0 mean \"no scissor\". Only pop when a scissor was actually
  pushed; unconditional disableScissor caused stack underflow on HUD frames
  whose UiDrawList has no clip runs."
  [^GuiGraphics gg ^floats clip-rects cur-clip clip]
  (when (not= clip cur-clip)
    (when (>= cur-clip 0)
      (.disableScissor gg))
    (when (>= clip 0)
      (let [b (* clip 4)]
        (.enableScissor gg (int (aget clip-rects b)) (int (aget clip-rects (unchecked-inc-int b)))
                        (int (+ (aget clip-rects b) (aget clip-rects (+ b 2))))
                        (int (+ (aget clip-rects (unchecked-inc-int b)) (aget clip-rects (+ b 3))))))))
  clip)

(defn- draw-ui-draw-list! [^GuiGraphics gg context stage ^UiDrawList dl]
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

(defn- draw-command! [^GuiGraphics graphics stage context ^RenderCommand command]
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
        ^GuiGraphics gg (if (map? graphics) (:graphics graphics) graphics)
        wanted (neutral/stage->render-stage stage)
        ^UiDrawList dl (.uiFor frame wanted)]
    (when (and dl (pos? (.count dl)) (instance? GuiGraphics gg))
      (draw-ui-draw-list! gg context stage dl))
    (doseq [^RenderPass pass (.passes frame)
            :when (= wanted (.stage pass))
            ^RenderCommand command (.commands pass)]
      (when (or (instance? GuiGraphics gg)
                (and (map? context) (some fn? (vals context))))
        (draw-command! gg stage context command))))
  frame)

(defn create []
  (presentation-bridge/install-text-metrics! (cgui-font/text-metrics))
  (neutral/install-renderer! (neutral/create profile) render!))

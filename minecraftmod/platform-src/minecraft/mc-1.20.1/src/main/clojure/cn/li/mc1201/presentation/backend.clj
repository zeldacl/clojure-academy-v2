(ns cn.li.mc1201.presentation.backend
  "Minecraft 1.20.1 Presentation backend seam.

   The backend deliberately consumes the neutral mcmod frame envelope. Mapping
   to BufferSource/GuiGraphics belongs in the version-owned render callback;
   no UI or effect policy is allowed here."
  (:require [cn.li.mcmod.runtime.presentation-backend :as neutral])
  (:import [cn.li.mcmod.runtime FramePacket RenderCommand RenderCommand$Batch
            RenderCommand$Beam RenderCommand$Billboard RenderCommand$CameraContribution
            RenderCommand$GlyphRun RenderCommand$Image RenderCommand$ItemPreview
            RenderCommand$Layer RenderCommand$Mesh RenderCommand$OrderBarrier
            RenderCommand$ParticleBatch RenderCommand$PopClip RenderCommand$PostProcess
            RenderCommand$PushClip RenderCommand$Quad RenderCommand$Ribbon RenderPass]
           [net.minecraft.client Minecraft]
           [net.minecraft.client.gui GuiGraphics Font]))

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

(defn- draw-command! [^GuiGraphics graphics stage context ^RenderCommand command]
  (condp instance? command
    RenderCommand$Quad
    (let [^RenderCommand$Quad c command]
      (.fill graphics (int (.x c)) (int (.y c))
                      (int (+ (.x c) (.width c))) (int (+ (.y c) (.height c)))
                      (.rgba c)))

    RenderCommand$Image
    (let [^RenderCommand$Image c command]
      (callback! context :draw-image!
                 [graphics stage (.textureId c) (.x c) (.y c) (.width c) (.height c) (.rgba c)]))

    RenderCommand$GlyphRun
    (let [^RenderCommand$GlyphRun c command
          ^Minecraft mc (Minecraft/getInstance)]
      (.drawString graphics (.-font mc) (.text c) (int (.x c)) (int (.y c)) (.rgba c)))

    RenderCommand$PushClip
    (let [^RenderCommand$PushClip c command]
      (.enableScissor graphics (int (.x c)) (int (.y c))
                               (int (+ (.x c) (.width c))) (int (+ (.y c) (.height c)))))

    RenderCommand$PopClip (.disableScissor graphics)

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

    RenderCommand$ItemPreview
    (let [^RenderCommand$ItemPreview c command]
      (callback! context :draw-item-preview! [graphics stage (.itemId c) (.x c) (.y c) (.scale c)]))

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
        graphics (if (map? graphics) (:graphics graphics) graphics)]
    (let [wanted (neutral/stage->render-stage stage)]
      (doseq [^RenderPass pass (.passes frame)
              :when (= wanted (.stage pass))
              ^RenderCommand command (.commands pass)]
        (when (or (instance? GuiGraphics graphics)
                  (and (map? context) (fn? (:draw-mesh! context))))
          (draw-command! graphics stage context command)))))
  frame)

(defn create []
  (neutral/install-renderer! (neutral/create profile) render!))

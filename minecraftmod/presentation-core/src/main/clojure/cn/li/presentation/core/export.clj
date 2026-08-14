(ns cn.li.presentation.core.export
  "Convert Core Render IR to the mcmod-neutral frame envelope.

   The conversion is deliberately kept on the Core side of the dependency
   seam. mcmod and minecraft/base never import presentation-core classes."
  (:import [cn.li.mcmod.runtime PresentationCommand PresentationFrame PresentationPass]
           [cn.li.presentation.core FramePacket RenderCommand$Beam
            RenderCommand$Billboard RenderCommand$CameraContribution
            RenderCommand$GlyphRun RenderCommand$Image RenderCommand$ItemPreview
            RenderCommand$Layer RenderCommand$Mesh RenderCommand$OrderBarrier
            RenderCommand$ParticleBatch RenderCommand$PopClip RenderCommand$PostProcess
            RenderCommand$PushClip RenderCommand$Quad RenderCommand$Ribbon]))

(defn- command-values [command]
  (cond
    (instance? RenderCommand$Quad command)
    [(double (.x ^RenderCommand$Quad command))
     (double (.y ^RenderCommand$Quad command))
     (double (.width ^RenderCommand$Quad command))
     (double (.height ^RenderCommand$Quad command))
     (int (.rgba ^RenderCommand$Quad command))]
    (instance? RenderCommand$Image command)
    [(int (.textureId ^RenderCommand$Image command))
     (double (.x ^RenderCommand$Image command))
     (double (.y ^RenderCommand$Image command))
     (double (.width ^RenderCommand$Image command))
     (double (.height ^RenderCommand$Image command))
     (int (.rgba ^RenderCommand$Image command))]
    (instance? RenderCommand$GlyphRun command)
    [(int (.fontId ^RenderCommand$GlyphRun command)) (.text ^RenderCommand$GlyphRun command)
     (double (.x ^RenderCommand$GlyphRun command)) (double (.y ^RenderCommand$GlyphRun command))
     (int (.rgba ^RenderCommand$GlyphRun command))]
    (instance? RenderCommand$PushClip command)
    [(double (.x ^RenderCommand$PushClip command)) (double (.y ^RenderCommand$PushClip command))
     (double (.width ^RenderCommand$PushClip command)) (double (.height ^RenderCommand$PushClip command))]
    (instance? RenderCommand$PopClip command) []
    (instance? RenderCommand$Layer command) [(int (.id ^RenderCommand$Layer command))]
    (instance? RenderCommand$Mesh command) [(int (.meshId ^RenderCommand$Mesh command)) (int (.materialId ^RenderCommand$Mesh command)) (int (.instanceCount ^RenderCommand$Mesh command))]
    (instance? RenderCommand$Billboard command) [(int (.textureId ^RenderCommand$Billboard command)) (int (.materialId ^RenderCommand$Billboard command)) (int (.instanceCount ^RenderCommand$Billboard command))
                                                 (double (.originX ^RenderCommand$Billboard command)) (double (.originY ^RenderCommand$Billboard command)) (double (.originZ ^RenderCommand$Billboard command))]
    (instance? RenderCommand$ParticleBatch command) [(int (.materialId ^RenderCommand$ParticleBatch command)) (int (.count ^RenderCommand$ParticleBatch command))
                                                     (double (.originX ^RenderCommand$ParticleBatch command)) (double (.originY ^RenderCommand$ParticleBatch command)) (double (.originZ ^RenderCommand$ParticleBatch command))]
    (instance? RenderCommand$Ribbon command) [(int (.materialId ^RenderCommand$Ribbon command)) (int (.pointCount ^RenderCommand$Ribbon command))]
    (instance? RenderCommand$Beam command) [(int (.materialId ^RenderCommand$Beam command)) (int (.segmentCount ^RenderCommand$Beam command))]
    (instance? RenderCommand$ItemPreview command) [(int (.itemId ^RenderCommand$ItemPreview command)) (double (.x ^RenderCommand$ItemPreview command)) (double (.y ^RenderCommand$ItemPreview command)) (double (.scale ^RenderCommand$ItemPreview command))]
    (instance? RenderCommand$CameraContribution command) [(double (.fovDelta ^RenderCommand$CameraContribution command)) (double (.shakeX ^RenderCommand$CameraContribution command)) (double (.shakeY ^RenderCommand$CameraContribution command)) (double (.roll ^RenderCommand$CameraContribution command))]
    (instance? RenderCommand$PostProcess command) [(int (.materialId ^RenderCommand$PostProcess command)) (double (.intensity ^RenderCommand$PostProcess command))]
    (instance? RenderCommand$OrderBarrier command) []
    :else (throw (ex-info "unsupported presentation command" {:class (class command)}))))

(defn- command-kind [command]
  (cond
    (instance? RenderCommand$Quad command) "quad"
    (instance? RenderCommand$Image command) "image"
    (instance? RenderCommand$GlyphRun command) "glyph-run"
    (instance? RenderCommand$PushClip command) "push-clip"
    (instance? RenderCommand$PopClip command) "pop-clip"
    (instance? RenderCommand$Layer command) "layer"
    (instance? RenderCommand$Mesh command) "mesh"
    (instance? RenderCommand$Billboard command) "billboard"
    (instance? RenderCommand$ParticleBatch command) "particle-batch"
    (instance? RenderCommand$Ribbon command) "ribbon"
    (instance? RenderCommand$Beam command) "beam"
    (instance? RenderCommand$ItemPreview command) "item-preview"
    (instance? RenderCommand$CameraContribution command) "camera-contribution"
    (instance? RenderCommand$PostProcess command) "post-process"
    (instance? RenderCommand$OrderBarrier command) "order-barrier"))

(defn- neutral-command [command]
  (PresentationCommand. (command-kind command) (command-values command)))

(defn neutral-frame
  "Convert an immutable Core FramePacket to the neutral mcmod envelope."
  [^FramePacket packet]
  (PresentationFrame.
    (.frameId packet)
    (mapv (fn [^cn.li.presentation.core.RenderPass pass]
            (PresentationPass.
              (.name (.stage pass))
              (mapv neutral-command (.commands pass))))
          (.passes packet))))

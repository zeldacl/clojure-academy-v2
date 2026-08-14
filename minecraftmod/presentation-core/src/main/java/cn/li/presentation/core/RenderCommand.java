package cn.li.presentation.core;

public sealed interface RenderCommand
        permits RenderCommand.Quad, RenderCommand.Image, RenderCommand.GlyphRun,
                RenderCommand.PushClip, RenderCommand.PopClip, RenderCommand.Layer,
                RenderCommand.Mesh, RenderCommand.Billboard, RenderCommand.ParticleBatch,
                RenderCommand.Ribbon, RenderCommand.Beam, RenderCommand.ItemPreview,
                RenderCommand.CameraContribution, RenderCommand.PostProcess,
                RenderCommand.OrderBarrier {
    record Quad(float x, float y, float width, float height, int rgba) implements RenderCommand {}
    record Image(int textureId, float x, float y, float width, float height, int rgba) implements RenderCommand {}
    record GlyphRun(int fontId, String text, float x, float y, int rgba) implements RenderCommand {
        public GlyphRun { text = text == null ? "" : text; }
    }
    record PushClip(float x, float y, float width, float height) implements RenderCommand {}
    record PopClip() implements RenderCommand {}
    record Layer(int id) implements RenderCommand {}
    /**
     * Version-neutral mesh submission.  The optional payload is immutable
     * presentation data (for example a geometry batch extracted by the
     * Clojure effect controller); it is never a backend draw-plan or a
     * Minecraft object.
     */
    record Mesh(int meshId, int materialId, int instanceCount, Object payload) implements RenderCommand {
        public Mesh(int meshId, int materialId, int instanceCount) {
            this(meshId, materialId, instanceCount, null);
        }
    }
    record Billboard(int textureId, int materialId, int instanceCount,
                     float originX, float originY, float originZ) implements RenderCommand {
        public Billboard(int textureId, int materialId, int instanceCount) {
            this(textureId, materialId, instanceCount, 0.0f, 0.0f, 0.0f);
        }
    }
    record ParticleBatch(int materialId, int count,
                         float originX, float originY, float originZ) implements RenderCommand {
        public ParticleBatch(int materialId, int count) {
            this(materialId, count, 0.0f, 0.0f, 0.0f);
        }
    }
    record Ribbon(int materialId, int pointCount) implements RenderCommand {}
    record Beam(int materialId, int segmentCount) implements RenderCommand {}
    record ItemPreview(int itemId, float x, float y, float scale) implements RenderCommand {}
    record CameraContribution(float fovDelta, float shakeX, float shakeY, float roll) implements RenderCommand {}
    record PostProcess(int materialId, float intensity) implements RenderCommand {}
    record OrderBarrier() implements RenderCommand {}
}

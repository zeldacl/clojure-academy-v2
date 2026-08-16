package cn.li.mcmod.runtime;

/**
 * Single neutral Render IR shared by the Presentation frame pipeline and
 * VFX Core. Sealed so every version backend's dispatch is exhaustive at
 * compile time; behaviour stays in Clojure backends.
 */
public sealed interface RenderCommand
        permits RenderCommand.Quad, RenderCommand.Image, RenderCommand.GlyphRun,
                RenderCommand.PushClip, RenderCommand.PopClip, RenderCommand.Layer,
                RenderCommand.Mesh, RenderCommand.Billboard, RenderCommand.ParticleBatch,
                RenderCommand.Ribbon, RenderCommand.Beam, RenderCommand.ItemPreview,
                RenderCommand.CameraContribution, RenderCommand.PostProcess,
                RenderCommand.OrderBarrier, RenderCommand.Batch {
    record Quad(float x, float y, float width, float height, int rgba) implements RenderCommand {}
    record Image(int textureId, float x, float y, float width, float height, int rgba) implements RenderCommand {}
    record GlyphRun(int fontId, String text, float x, float y, int rgba) implements RenderCommand {
        public GlyphRun { text = text == null ? "" : text; }
    }
    record PushClip(float x, float y, float width, float height) implements RenderCommand {}
    record PopClip() implements RenderCommand {}
    record Layer(int id) implements RenderCommand {}
    /**
     * Version-neutral mesh submission. The optional payload is immutable
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
    /**
     * Neutral VFX effect batch. payload is immutable data or a ByteBuffer;
     * never a backend draw-plan or a Minecraft object. Gives vfx-core's
     * sample batches a typed home in the same Render IR that UI commands
     * travel through, instead of a second frame envelope.
     */
    record Batch(RenderStage stage, String primitive, String material, String variant,
                long layoutVersion, long count, String sortMode, Object payload) implements RenderCommand {
        public Batch {
            if (stage == null) throw new NullPointerException("stage");
            if (primitive == null || primitive.isBlank()) throw new IllegalArgumentException("primitive");
            if (count < 0) throw new IllegalArgumentException("count");
        }
    }
}

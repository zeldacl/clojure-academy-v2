package cn.li.mcmod.runtime;

import java.util.List;

/**
 * Single neutral Render IR shared by the Presentation frame pipeline and
 * VFX Core. Sealed so every version backend's dispatch is exhaustive at
 * compile time; behaviour stays in Clojure backends.
 */
public sealed interface RenderCommand
        permits RenderCommand.UiQuadBatch, RenderCommand.UiImageBatch, RenderCommand.UiText,
                RenderCommand.UiItemPreview, RenderCommand.UiModelPreview,
                RenderCommand.PushClip, RenderCommand.PopClip, RenderCommand.Transform, RenderCommand.Mask, RenderCommand.Layer,
                RenderCommand.Mesh, RenderCommand.Billboard, RenderCommand.ParticleBatch,
                RenderCommand.Ribbon, RenderCommand.Beam,
                RenderCommand.CameraContribution, RenderCommand.PostProcess,
                RenderCommand.AudioContribution,
                RenderCommand.OrderBarrier, RenderCommand.Batch {
    record UiQuad(float x, float y, float width, float height, int rgba) {}
    record UiQuadBatch(List<UiQuad> quads) implements RenderCommand {
        public UiQuadBatch { quads = List.copyOf(quads == null ? List.of() : quads); }
    }
    record UiImage(float x, float y, float width, float height, int rgba) {}
    record UiImageBatch(UiResourceRef resource, List<UiImage> images) implements RenderCommand {
        public UiImageBatch {
            if (resource == null) throw new NullPointerException("resource");
            images = List.copyOf(images == null ? List.of() : images);
        }
    }
    record UiText(int fontId, String text, float x, float y, int rgba, float fontSize) implements RenderCommand {
        public UiText {
            text = text == null ? "" : text;
            if (fontSize <= 0.0f) fontSize = 8.0f;
        }
        /** Backward-compatible ctor; default MSDF size matches tutorial markdown. */
        public UiText(int fontId, String text, float x, float y, int rgba) {
            this(fontId, text, x, y, rgba, 8.0f);
        }
    }
    record UiItemPreview(int itemId, float x, float y, float scale) implements RenderCommand {}
    record UiModelPreview(String modelId, float x, float y, float width, float height) implements RenderCommand {
        public UiModelPreview { modelId = modelId == null ? "" : modelId; }
    }
    record PushClip(float x, float y, float width, float height) implements RenderCommand {}
    record PopClip() implements RenderCommand {}
    /** Projective/affine transform payload interpreted by the version backend. */
    record Transform(String transformId, Object payload) implements RenderCommand {
        public Transform { transformId = transformId == null ? "identity" : transformId; }
    }
    /** Declarative mask payload; backend owns stencil/alpha implementation. */
    record Mask(String maskId, Object payload) implements RenderCommand {
        public Mask { maskId = maskId == null ? "none" : maskId; }
    }
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
    record CameraContribution(float fovDelta, float shakeX, float shakeY, float roll) implements RenderCommand {}
    record PostProcess(int materialId, float intensity) implements RenderCommand {}
    /** Neutral audio intent paired with a frame; backend resolves the sound id. */
    record AudioContribution(String soundId, float volume, float pitch) implements RenderCommand {
        public AudioContribution { soundId = soundId == null ? "" : soundId; }
    }
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

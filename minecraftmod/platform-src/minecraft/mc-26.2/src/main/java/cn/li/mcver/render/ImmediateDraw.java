package cn.li.mcver.render;

import org.joml.Matrix4f;

/**
 * Version seam for immediate-mode mesh upload.
 *
 * <p>26.2 removed the {@code Tesselator}/{@code BufferUploader}/
 * {@code VertexFormat.Mode} immediate-draw path as part of the
 * RenderPipeline/GpuBuffer render rewrite (topology now lives on
 * {@code PrimitiveTopology} + {@code RenderPipeline}, uploads go through
 * {@code CommandEncoder}/{@code GpuBuffer} instead of a single static
 * {@code Tesselator} buffer). Porting the real immediate-draw path is
 * deferred to the client-render phase of the 26.2 port; this seam keeps the
 * 1.21.1 public surface intact and stubs the implementation so
 * {@code cn.li.mcver.render.ImmediateDraw} compiles and satisfies the
 * version-seam parity gate. Calling any drawing method throws
 * {@link UnsupportedOperationException} until the real implementation
 * lands.
 */
public final class ImmediateDraw {
    private ImmediateDraw() {
    }

    public enum Mode {
        QUADS
    }

    public enum Format {
        POSITION_TEX,
        POSITION_COLOR
    }

    public static void begin(Mode mode, Format format) {
        throw unsupported();
    }

    public static Vertex vertex(Matrix4f pose, float x, float y, float z) {
        throw unsupported();
    }

    public static Vertex vertex(float x, float y, float z) {
        throw unsupported();
    }

    public static void draw() {
        throw unsupported();
    }

    /**
     * One POSITION_TEX axis-aligned quad. Caller must already set shader + texture.
     */
    public static void texturedQuad(
            Matrix4f pose,
            float x1, float y1, float x2, float y2, float z,
            float u0, float u1, float v0, float v1) {
        throw unsupported();
    }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException(
            "ImmediateDraw is not yet implemented on 26.2 (RenderPipeline/GpuBuffer rewrite); "
                + "see cn.li.mcver.render.ImmediateDraw");
    }

    public static final class Vertex {
        private Vertex() {
        }

        public Vertex uv(float u, float v) {
            throw unsupported();
        }

        public Vertex color(float r, float g, float b, float a) {
            throw unsupported();
        }

        public Vertex color(int argb) {
            throw unsupported();
        }

        public void endVertex() {
            throw unsupported();
        }
    }
}

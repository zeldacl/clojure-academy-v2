package cn.li.mc1201.client.font.msdf;

import org.lwjgl.stb.STBTruetype;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * Bakes signed distance fields via STB {@code stbtt_GetGlyphSDF} (analytic SDF).
 * RGBA stores the same normalized distance in all channels for the MSDF shader median path.
 */
public final class MsdfEngine {

    private static final byte SDF_ON_EDGE = (byte) 128;

    private MsdfEngine() {
    }

    public static final class MsdfPixels {
        public final int width;
        public final int height;
        /** Padding baked into {@link #width}/{@link #height} on all sides. */
        public final int pad;
        /** RGBA bytes, row-major; r=g=b=a = STB SDF byte (edge at 128). */
        public final byte[] rgba;

        public MsdfPixels(final int width, final int height, final int pad, final byte[] rgba) {
            this.width = width;
            this.height = height;
            this.pad = pad;
            this.rgba = rgba;
        }
    }

    public static MsdfPixels generate(
            final MsdfFontFace face,
            final int glyphIndex,
            final int pxRange) {
        return generate(face, glyphIndex, pxRange, pxRange);
    }

    public static MsdfPixels generate(
            final MsdfFontFace face,
            final int glyphIndex,
            final int sdfPxRange,
            final int outerPad) {
        final int pad = Math.max(sdfPxRange, outerPad);
        final float pixelDistScale = SDF_ON_EDGE / (float) sdfPxRange;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            final IntBuffer wBuf = stack.mallocInt(1);
            final IntBuffer hBuf = stack.mallocInt(1);
            final IntBuffer xoffBuf = stack.mallocInt(1);
            final IntBuffer yoffBuf = stack.mallocInt(1);
            final ByteBuffer sdf = STBTruetype.stbtt_GetGlyphSDF(
                    face.fontInfo(),
                    face.scale(),
                    glyphIndex,
                    pad,
                    SDF_ON_EDGE,
                    pixelDistScale,
                    wBuf,
                    hBuf,
                    xoffBuf,
                    yoffBuf);

            if (sdf == null) {
                return rasterFallback(face, glyphIndex, pad, sdfPxRange, outerPad);
            }

            final int w = wBuf.get(0);
            final int h = hBuf.get(0);
            final byte[] rgba = new byte[w * h * 4];
            for (int i = 0; i < w * h; i++) {
                final byte v = sdf.get(i);
                final int idx = i * 4;
                rgba[idx] = v;
                rgba[idx + 1] = v;
                rgba[idx + 2] = v;
                rgba[idx + 3] = v;
            }
            STBTruetype.stbtt_FreeSDF(sdf);
            return new MsdfPixels(w, h, pad, rgba);
        }
    }

  /** Bitmap fallback when STB SDF fails for a glyph index. */
    private static MsdfPixels rasterFallback(
            final MsdfFontFace face,
            final int glyphIndex,
            final int pad,
            final int sdfPxRange,
            final int outerPad) {
        final float scale = face.scale();
        final int[] bb = face.getBitmapBox(glyphIndex);
        final int bx0 = bb[0];
        final int by0 = bb[1];
        final int bx1 = bb[2];
        final int by1 = bb[3];
        final int negY0 = -by0;
        final int negY1 = -by1;
        final int glyphW = Math.max(1, bx1 - bx0);
        final int glyphH = Math.max(1, negY0 - negY1);
        final int bakePad = Math.max(pad, Math.max(sdfPxRange, outerPad));
        final int width = Math.max(1, glyphW + 2 * bakePad);
        final int height = Math.max(1, glyphH + 2 * bakePad);

        final ByteBuffer coverage = MemoryUtil.memAlloc(glyphW * glyphH);
        try {
            STBTruetype.stbtt_MakeGlyphBitmapSubpixel(
                    face.fontInfo(), coverage, glyphW, glyphH, glyphW,
                    scale, scale, 0.0f, 0.0f, glyphIndex);

            final byte[] rgba = new byte[width * height * 4];
            for (int row = 0; row < height; row++) {
                for (int col = 0; col < width; col++) {
                    final int idx = (row * width + col) * 4;
                    final int innerRow = row - bakePad;
                    final int innerCol = col - bakePad;
                    byte cov = 0;
                    if (innerRow >= 0 && innerRow < glyphH && innerCol >= 0 && innerCol < glyphW) {
                        cov = coverage.get(innerRow * glyphW + innerCol);
                    }
                    final float dist = ((cov & 0xFF) / 255.0f - 0.5f) * 2.0f * sdfPxRange;
                    final byte v = distanceToByte(dist, sdfPxRange);
                    rgba[idx] = v;
                    rgba[idx + 1] = v;
                    rgba[idx + 2] = v;
                    rgba[idx + 3] = v;
                }
            }
            return new MsdfPixels(width, height, bakePad, rgba);
        } finally {
            MemoryUtil.memFree(coverage);
        }
    }

    private static byte distanceToByte(final float dist, final int pxRange) {
        final float normalized = 0.5f + dist / (2.0f * pxRange);
        final int v = Math.round(Math.max(0.0f, Math.min(1.0f, normalized)) * 255.0f);
        return (byte) v;
    }
}

package cn.li.mc1201.client.font.msdf;

import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.stb.STBTruetype;
import org.lwjgl.stb.STBTTVertex;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * STB TrueType font face loaded from a system font file (no AWT).
 */
public final class MsdfFontFace implements AutoCloseable {

    private final ByteBuffer fontData;
    private final STBTTFontinfo fontInfo;
    private final float scale;
    private final int ascent;
    private final int descent;
    private final int lineGap;

    public MsdfFontFace(final Path fontPath, final float pixelHeight) throws IOException {
        final byte[] raw = Files.readAllBytes(fontPath);
        final byte[] ttfBytes = extractFirstTtfIfCollection(raw);
        this.fontData = MemoryUtil.memAlloc(ttfBytes.length);
        this.fontData.put(ttfBytes);
        this.fontData.flip();

        this.fontInfo = STBTTFontinfo.create();
        if (!STBTruetype.stbtt_InitFont(fontInfo, fontData)) {
            MemoryUtil.memFree(fontData);
            throw new IOException("stbtt_InitFont failed for " + fontPath);
        }

        this.scale = STBTruetype.stbtt_ScaleForPixelHeight(fontInfo, pixelHeight);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final IntBuffer ascentBuf = stack.mallocInt(1);
            final IntBuffer descentBuf = stack.mallocInt(1);
            final IntBuffer lineGapBuf = stack.mallocInt(1);
            STBTruetype.stbtt_GetFontVMetrics(fontInfo, ascentBuf, descentBuf, lineGapBuf);
            this.ascent = ascentBuf.get(0);
            this.descent = descentBuf.get(0);
            this.lineGap = lineGapBuf.get(0);
        }
    }

    public STBTTFontinfo fontInfo() {
        return fontInfo;
    }

    public float scale() {
        return scale;
    }

    public int ascent() {
        return ascent;
    }

    public int descent() {
        return descent;
    }

    public int lineGap() {
        return lineGap;
    }

    public int findGlyphIndex(final int codePoint) {
        return STBTruetype.stbtt_FindGlyphIndex(fontInfo, codePoint);
    }

    public boolean hasGlyph(final int codePoint) {
        return findGlyphIndex(codePoint) != 0;
    }

    public float getAdvance(final int glyphIndex) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final IntBuffer advance = stack.mallocInt(1);
            final IntBuffer lsb = stack.mallocInt(1);
            STBTruetype.stbtt_GetGlyphHMetrics(fontInfo, glyphIndex, advance, lsb);
            return advance.get(0) * scale;
        }
    }

    public float getLeftSideBearing(final int glyphIndex) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final IntBuffer advance = stack.mallocInt(1);
            final IntBuffer lsb = stack.mallocInt(1);
            STBTruetype.stbtt_GetGlyphHMetrics(fontInfo, glyphIndex, advance, lsb);
            return lsb.get(0) * scale;
        }
    }

    public int[] getGlyphBox(final int glyphIndex) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final IntBuffer x0 = stack.mallocInt(1);
            final IntBuffer y0 = stack.mallocInt(1);
            final IntBuffer x1 = stack.mallocInt(1);
            final IntBuffer y1 = stack.mallocInt(1);
            STBTruetype.stbtt_GetGlyphBox(fontInfo, glyphIndex, x0, y0, x1, y1);
            return new int[] { x0.get(0), y0.get(0), x1.get(0), y1.get(0) };
        }
    }

    /** Pixel bitmap box at {@link #scale()}, matching vanilla {@code TrueTypeGlyphProvider}. */
    public int[] getBitmapBox(final int glyphIndex) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final IntBuffer x0 = stack.mallocInt(1);
            final IntBuffer y0 = stack.mallocInt(1);
            final IntBuffer x1 = stack.mallocInt(1);
            final IntBuffer y1 = stack.mallocInt(1);
            STBTruetype.stbtt_GetGlyphBitmapBoxSubpixel(
                    fontInfo, glyphIndex, scale, scale, 0.0f, 0.0f, x0, y0, x1, y1);
            return new int[] { x0.get(0), y0.get(0), x1.get(0), y1.get(0) };
        }
    }

    public float ascentPixels() {
        return ascent * scale;
    }

    public STBTTVertex.Buffer getGlyphShape(final int glyphIndex) {
        return STBTruetype.stbtt_GetGlyphShape(fontInfo, glyphIndex);
    }

    @Override
    public void close() {
        if (fontData != null) {
            MemoryUtil.memFree(fontData);
        }
    }

    private static byte[] extractFirstTtfIfCollection(final byte[] raw) {
        if (raw.length < 12) {
            return raw;
        }
        final String tag = new String(raw, 0, 4, StandardCharsets.US_ASCII);
        if (!"ttcf".equals(tag)) {
            return raw;
        }
        final ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        final int firstOffset = buf.getInt(12);
        if (firstOffset <= 0 || firstOffset >= raw.length) {
            return raw;
        }
        final int numTables = Short.toUnsignedInt(buf.getShort(firstOffset + 4));
        final int headerSize = 12 + 16 * numTables;
        int maxEnd = headerSize;
        for (int i = 0; i < numTables; i++) {
            final int rb = firstOffset + 12 + i * 16;
            final int end = (buf.getInt(rb + 8) + buf.getInt(rb + 12)) - firstOffset;
            if (end > maxEnd) {
                maxEnd = end;
            }
        }
        final byte[] result = new byte[maxEnd];
        System.arraycopy(raw, firstOffset, result, 0, headerSize);
        final ByteBuffer resultBuf = ByteBuffer.wrap(result).order(ByteOrder.BIG_ENDIAN);
        for (int i = 0; i < numTables; i++) {
            final int rb = 12 + i * 16;
            final int origOff = buf.getInt(firstOffset + rb + 8);
            final int len = buf.getInt(firstOffset + rb + 12);
            final int newOff = origOff - firstOffset;
            resultBuf.putInt(rb + 8, newOff);
            System.arraycopy(raw, origOff, result, newOff, len);
        }
        return result;
    }
}

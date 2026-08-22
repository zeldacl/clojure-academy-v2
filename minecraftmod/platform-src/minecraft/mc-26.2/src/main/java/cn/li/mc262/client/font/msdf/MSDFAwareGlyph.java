package cn.li.mc262.client.font.msdf;

import com.mojang.blaze3d.font.GlyphBitmap;
import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.UnbakedGlyph;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;

/**
 * Dual-mode glyph wrapper for 26.2's {@link UnbakedGlyph} model.
 * <ul>
 *   <li><b>Proportional</b> — passes through the original glyph unchanged.</li>
 *   <li><b>Monospace</b> — overrides {@link GlyphInfo#getAdvance()} to a fixed
 *       grid width via a wrapping info().</li>
 * </ul>
 *
 * <p>Vertical placement reproduces the 1.20.1 convention exactly (26.2's
 * GlyphBitmap.getTop() is vanilla's {@code 7.0F - bearingTop}):</p>
 * <pre>
 *   1.20.1 quad top = y + (pixelHeight - descenderPx - 3) + y0_stb
 *                     (3 = 1.20.1 BakedGlyph.render constant)
 *   26.2 quad top    = y + (7 - bitmap_top)                (7 = vanilla ascent)
 * </pre>
 * The per-glyph compensation is therefore
 * {@code (pixelHeight - descenderPx - 3) - 7 + y0_stb + bitmap_top}, where
 * {@code y0_stb} is the STB rasterizer's bitmap top and {@code bitmap_top}
 * the FreeType one — no measured magic numbers, exact for every glyph.
 * The bake() wraps the Stitcher so the FreeType glyph's bitmap can be
 * shifted before it is stitched into the font texture.
 */
public final class MSDFAwareGlyph implements UnbakedGlyph {

    /** 26.2's GlyphBitmap.getTop() = ASCENT - bearingTop. */
    private static final float VANILLA_ASCENT = 7.0f;
    /** 1.20.1's BakedGlyph.render subtracted this from every glyph quad. */
    private static final float LEGACY_RENDER_SHIFT = 3.0f;

    private final UnbakedGlyph original;
    private final boolean monospace;
    private final float monospaceAdvance;
    private final GlyphInfo monospaceInfo;
    private final int codePoint;
    private final MsdfFontFace face;

    public MSDFAwareGlyph(final UnbakedGlyph original, final boolean monospace,
                          final float monospaceAdvance, final int codePoint,
                          final MsdfFontFace face) {
        this.original = original;
        this.monospace = monospace;
        this.monospaceAdvance = monospaceAdvance;
        this.codePoint = codePoint;
        this.face = face;
        final GlyphInfo base = original.info();
        this.monospaceInfo = new GlyphInfo() {
            @Override
            public float getAdvance() {
                return monospaceAdvance;
            }

            @Override
            public float getBoldOffset() {
                return base.getBoldOffset();
            }

            @Override
            public float getShadowOffset() {
                return base.getShadowOffset();
            }
        };
    }

    @Override
    public GlyphInfo info() {
        return monospace ? monospaceInfo : original.info();
    }

    @Override
    public BakedGlyph bake(final Stitcher stitcher) {
        return original.bake(new Stitcher() {
            @Override
            public BakedGlyph stitch(final GlyphInfo info, final GlyphBitmap glyphBitmap) {
                return stitcher.stitch(info,
                        new VerticallyShiftedGlyphBitmap(
                                glyphBitmap, codePoint, face));
            }

            @Override
            public BakedGlyph getMissing() {
                return stitcher.getMissing();
            }
        });
    }

    private static final class VerticallyShiftedGlyphBitmap implements GlyphBitmap {
        private final GlyphBitmap delegate;
        private final int codePoint;
        private final MsdfFontFace face;

        VerticallyShiftedGlyphBitmap(final GlyphBitmap delegate,
                                     final int codePoint,
                                     final MsdfFontFace face) {
            this.delegate = delegate;
            this.codePoint = codePoint;
            this.face = face;
        }

        @Override
        public int getPixelWidth() {
            return delegate.getPixelWidth();
        }

        @Override
        public int getPixelHeight() {
            return delegate.getPixelHeight();
        }

        @Override
        public void upload(final int x, final int y, final GpuTexture texture) {
            delegate.upload(x, y, texture);
        }

        @Override
        public boolean isColored() {
            return delegate.isColored();
        }

        @Override
        public float getOversample() {
            return delegate.getOversample();
        }

        @Override
        public float getTop() {
            return delegate.getTop() + verticalShift();
        }

        @Override
        public float getBottom() {
            return delegate.getBottom() + verticalShift();
        }

        /**
         * Standard 1.20.1 -> 26.2 placement conversion, per glyph:
         *  1.20.1 top = y + (pixelHeight - descenderPx - 3) + y0_stb
         *  26.2 top  = y + 7 - bitmap_top
         * shift 26.2's top down by the difference of the two formulas.
         */
        private float verticalShift() {
            return (MsdfFontManager.DESIGN_PIXEL_HEIGHT
                    - face.descenderPixels() - LEGACY_RENDER_SHIFT)
                    - VANILLA_ASCENT
                    + face.stbGlyphTop(codePoint)
                    + delegate.getBearingTop();
        }
    }
}

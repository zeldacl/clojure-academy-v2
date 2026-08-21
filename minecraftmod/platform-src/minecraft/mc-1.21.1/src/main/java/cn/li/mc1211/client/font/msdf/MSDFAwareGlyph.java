package cn.li.mc1211.client.font.msdf;

import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.SheetGlyphInfo;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;

import java.util.function.Function;

/**
 * Dual-mode glyph wrapper.
 * <ul>
 *   <li><b>Proportional</b> — passes through the original {@link GlyphInfo} unchanged.</li>
 *   <li><b>Monospace</b> — overrides {@link #getAdvance()} to a fixed grid width.
 *       Glyph rendering is not shifted (centering would require a Mixin on
 *       {@code BakedGlyph.render} because its fields are private).</li>
 * </ul>
 *
 * <p>Vertical placement reproduces the 1.20.1 convention exactly:</p>
 * <pre>
 *   1.20.1 quad top = y + (pixelHeight - descenderPx - 3) + y0_stb
 *                     (3 = 1.20.1 BakedGlyph.render constant)
 *   1.21.1 quad top = y + (7 - bitmap_top)                  (7 = 1.21.1 ascent)
 * </pre>
 * The per-glyph compensation is therefore
 * {@code (pixelHeight - descenderPx - 3) - 7 + y0_stb + bitmap_top}, where
 * {@code y0_stb} is the STB rasterizer's bitmap top and {@code bitmap_top}
 * the FreeType one — no measured magic numbers, exact for every glyph.
 */
public class MSDFAwareGlyph implements GlyphInfo {

    /** 1.21.1's SheetGlyphInfo.getTop() = ASCENT - bearingTop. */
    private static final float VANILLA_ASCENT = 7.0f;
    /** 1.20.1's BakedGlyph.render subtracted this from every glyph quad. */
    private static final float LEGACY_RENDER_SHIFT = 3.0f;

    private final GlyphInfo original;
    private final boolean monospace;
    private final float monospaceAdvance;
    private final int codePoint;
    private final MsdfFontFace face;

    public MSDFAwareGlyph(final GlyphInfo original, final boolean monospace,
                          final float monospaceAdvance, final int codePoint,
                          final MsdfFontFace face) {
        this.original = original;
        this.monospace = monospace;
        this.monospaceAdvance = monospaceAdvance;
        this.codePoint = codePoint;
        this.face = face;
    }

    @Override
    public float getAdvance() {
        return monospace ? monospaceAdvance : original.getAdvance();
    }

    @Override
    public float getBoldOffset() {
        return original.getBoldOffset();
    }

    @Override
    public BakedGlyph bake(final Function<SheetGlyphInfo, BakedGlyph> baker) {
        return original.bake(sgi -> baker.apply(
                new VerticallyShiftedSheetGlyph(sgi, codePoint, face)));
    }

    private static final class VerticallyShiftedSheetGlyph implements SheetGlyphInfo {
        private final SheetGlyphInfo delegate;
        private final int codePoint;
        private final MsdfFontFace face;

        VerticallyShiftedSheetGlyph(final SheetGlyphInfo delegate,
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
        public void upload(final int x, final int y) {
            delegate.upload(x, y);
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
         * Standard 1.20.1 -> 1.21.1 placement conversion, per glyph:
         *  1.20.1 top = y + (pixelHeight - descenderPx - 3) + y0_stb
         *  1.21.1 top = y + 7 - bitmap_top
         * shift 1.21.1's top down by the difference of the two formulas.
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

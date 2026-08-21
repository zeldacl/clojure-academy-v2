package cn.li.mc262.client.font.msdf;

import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.UnbakedGlyph;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;

/**
 * Dual-mode glyph wrapper for 26.2's {@link UnbakedGlyph} model.
 * <ul>
 *   <li><b>Proportional</b> — passes through the original glyph unchanged.</li>
 *   <li><b>Monospace</b> — overrides {@link GlyphInfo#getAdvance()} to a fixed
 *       grid width via a wrapping info().</li>
 * </ul>
 */
public final class MSDFAwareGlyph implements UnbakedGlyph {

    private final UnbakedGlyph original;
    private final boolean monospace;
    private final float monospaceAdvance;
    private final GlyphInfo monospaceInfo;

    public MSDFAwareGlyph(final UnbakedGlyph original, final boolean monospace,
                          final float monospaceAdvance) {
        this.original = original;
        this.monospace = monospace;
        this.monospaceAdvance = monospaceAdvance;
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
        return original.bake(stitcher);
    }
}

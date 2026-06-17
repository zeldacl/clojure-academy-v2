package cn.li.mc1201.client.font.msdf;

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
 */
public class MSDFAwareGlyph implements GlyphInfo {

    private final GlyphInfo original;
    private final boolean monospace;
    private final float monospaceAdvance;

    public MSDFAwareGlyph(final GlyphInfo original, final boolean monospace,
                          final float monospaceAdvance) {
        this.original = original;
        this.monospace = monospace;
        this.monospaceAdvance = monospaceAdvance;
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
        return original.bake(baker);
    }
}

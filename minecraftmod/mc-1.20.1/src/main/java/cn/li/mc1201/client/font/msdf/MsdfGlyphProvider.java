package cn.li.mc1201.client.font.msdf;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import com.mojang.blaze3d.font.GlyphProvider;
import com.mojang.blaze3d.font.GlyphInfo;
import javax.annotation.Nullable;

public final class MsdfGlyphProvider implements GlyphProvider {

    private final MsdfFontFace face;
    private final MsdfAtlas atlas;
    private final int pxRange;
    private final IntSet supportedGlyphs;

    public MsdfGlyphProvider(final MsdfFontFace face, final MsdfAtlas atlas, final int pxRange) {
        this.face = face;
        this.atlas = atlas;
        this.pxRange = pxRange;
        this.supportedGlyphs = scanSupportedGlyphs(face);
    }

    private static IntSet scanSupportedGlyphs(final MsdfFontFace face) {
        final IntOpenHashSet set = new IntOpenHashSet();
        for (int cp = 0; cp <= 0x10FFFF; cp++) {
            if (face.hasGlyph(cp)) {
                set.add(cp);
            }
        }
        return set;
    }

    @Override
    @Nullable
    public GlyphInfo getGlyph(final int codePoint) {
        final int glyphIndex = face.findGlyphIndex(codePoint);
        if (glyphIndex == 0) {
            return null;
        }
        atlas.prefetchGlyph(face, glyphIndex);
        return new MsdfGlyphInfo(face, atlas, glyphIndex, pxRange);
    }

    @Override
    public IntSet getSupportedGlyphs() {
        return supportedGlyphs;
    }

    @Override
    public void close() {
        face.close();
    }

    public MsdfFontFace face() {
        return face;
    }
}

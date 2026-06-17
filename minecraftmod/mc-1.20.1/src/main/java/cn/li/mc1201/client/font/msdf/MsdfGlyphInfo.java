package cn.li.mc1201.client.font.msdf;

import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.SheetGlyphInfo;
import net.minecraft.client.gui.font.GlyphRenderTypes;

import java.util.function.Function;

public final class MsdfGlyphInfo implements GlyphInfo {

    private final MsdfFontFace face;
    private final MsdfAtlas atlas;
    private final int glyphIndex;
    private final int pxRange;
    private final float advance;

    public MsdfGlyphInfo(final MsdfFontFace face, final MsdfAtlas atlas, final int glyphIndex, final int pxRange) {
        this.face = face;
        this.atlas = atlas;
        this.glyphIndex = glyphIndex;
        this.pxRange = pxRange;
        this.advance = face.getAdvance(glyphIndex);
    }

    @Override
    public float getAdvance() {
        return advance;
    }

    @Override
    public BakedGlyph bake(final Function<SheetGlyphInfo, BakedGlyph> baker) {
        final MsdfAtlas.BakedSlot slot = atlas.bakeGlyph(face, glyphIndex);
        final GlyphRenderTypes renderTypes =
                MsdfRenderTypes.glyphRenderTypes(slot.textureId());
        return new BakedGlyph(
                renderTypes,
                slot.u0(), slot.u1(), slot.v0(), slot.v1(),
                slot.left(), slot.right(), slot.up(), slot.down());
    }
}

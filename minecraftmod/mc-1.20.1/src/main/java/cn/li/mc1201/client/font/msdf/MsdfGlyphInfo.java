package cn.li.mc1201.client.font.msdf;

import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.SheetGlyphInfo;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;

import java.util.function.Function;

/**
 * Bakes STB SDF into the vanilla {@link net.minecraft.client.gui.font.FontSet} atlas via
 * {@code stitch} + luminance upload, so {@code rendertype_text} handles GUI drawing.
 */
public final class MsdfGlyphInfo implements GlyphInfo {

    private final MsdfFontFace face;
    private final MsdfAtlas atlas;
    private final int glyphIndex;
    private final float advance;

    public MsdfGlyphInfo(final MsdfFontFace face, final MsdfAtlas atlas, final int glyphIndex) {
        this.face = face;
        this.atlas = atlas;
        this.glyphIndex = glyphIndex;
        this.advance = face.getAdvance(glyphIndex);
    }

    @Override
    public float getAdvance() {
        return advance;
    }

    @Override
    public float getBoldOffset() {
        return 0.0f;
    }

    @Override
    public BakedGlyph bake(final Function<SheetGlyphInfo, BakedGlyph> baker) {
        final MsdfEngine.MsdfPixels pixels = atlas.pixelsFor(face, glyphIndex);
        final int pad = pixels.pad;
        final int fullW = pixels.width;

        final int[] bb = face.getBitmapBox(glyphIndex);
        final int bx0 = bb[0];
        final int bx1 = bb[2];
        final int by0 = bb[1];
        final int by1 = bb[3];
        final int negY0 = -by0;
        final int negY1 = -by1;
        final int innerW = Math.max(1, bx1 - bx0);
        final int innerH = Math.max(1, negY0 - negY1);

        final float bearingX = face.getLeftSideBearing(glyphIndex) + bx0;
        final float bearingY = face.ascentPixels() - negY0;

        return baker.apply(new SheetGlyphInfo() {
            @Override
            public int getPixelWidth() {
                return innerW;
            }

            @Override
            public int getPixelHeight() {
                return innerH;
            }

            @Override
            public float getOversample() {
                return 1.0f;
            }

            @Override
            public float getBearingX() {
                return bearingX;
            }

            @Override
            public float getBearingY() {
                return bearingY;
            }

            @Override
            public boolean isColored() {
                return false;
            }

            @Override
            public void upload(final int atlasX, final int atlasY) {
                final NativeImage image =
                        new NativeImage(NativeImage.Format.LUMINANCE, innerW, innerH, false);
                for (int row = 0; row < innerH; row++) {
                    for (int col = 0; col < innerW; col++) {
                        final int src = ((row + pad) * fullW + (col + pad)) * 4;
                        image.setPixelLuminance(col, row, pixels.rgba[src]);
                    }
                }
                image.upload(0, atlasX, atlasY, 0, 0, innerW, innerH, false, true);
                image.close();
            }
        });
    }
}

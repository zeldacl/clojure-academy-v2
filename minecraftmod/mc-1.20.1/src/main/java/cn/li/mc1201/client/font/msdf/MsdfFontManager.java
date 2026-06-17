package cn.li.mc1201.client.font.msdf;

import com.mojang.blaze3d.font.GlyphProvider;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;

import java.nio.file.Path;
import java.util.List;

/**
 * Shadow MSDF font: isolated FontSet + Font for mod CGUI text.
 */
public final class MsdfFontManager {

    public static final ResourceLocation SHADOW_FONT_ID = new ResourceLocation("my_mod", "msdf_shadow");

    /**
     * AcademyCraft {@code ClientResources} loads AWT {@code Font.PLAIN} at 24pt.
     * LambdaLib2 {@code TrueTypeFont} cell: {@code (int)(font.getSize() * 1.4)}.
     * Used for documentation / future tuning only; screen contract stays {@code :font-size N} = N px.
     */
    public static final float AC_AWT_FONT_PT = 24.0f;
    public static final float AC_CHAR_SIZE_FACTOR = 1.4f;
    public static final float AC_CHAR_SIZE = AC_AWT_FONT_PT * AC_CHAR_SIZE_FACTOR;

    /** STB em height when baking MSDF (screen px = {@code :font-size N} via CGUI scale). */
    public static final float DESIGN_PIXEL_HEIGHT = 8.0f;

    /**
     * Divisor for CGUI {@code :font-size N}. {@code scale = N / CGUI_BASE_HEIGHT};
     * with typographic glyph bounds, screen height = N pixels.
     */
    public static final float CGUI_BASE_HEIGHT = 8.0f;

    private static volatile boolean initialized;
    private static volatile boolean available;
    private static Font shadowFont;
    private static MsdfAtlas atlas;
    private static MsdfGlyphProvider provider;

    private MsdfFontManager() {
    }

    public static boolean init(final Path fontPath) {
        if (initialized) {
            return available;
        }
        synchronized (MsdfFontManager.class) {
            if (initialized) {
                return available;
            }
            initialized = true;
            try {
                final Minecraft mc = Minecraft.getInstance();
                final TextureManager textureManager = mc.getTextureManager();
                final MsdfFontFace face = new MsdfFontFace(fontPath, DESIGN_PIXEL_HEIGHT);
                atlas = new MsdfAtlas(textureManager);
                provider = new MsdfGlyphProvider(face, atlas, MsdfAtlas.DEFAULT_PX_RANGE);

                final FontSet fontSet = new FontSet(textureManager, SHADOW_FONT_ID);
                fontSet.reload(List.<GlyphProvider>of(provider));
                shadowFont = new Font(rl -> fontSet, false);
                available = true;
            } catch (Exception e) {
                available = false;
                shadowFont = null;
            }
            return available;
        }
    }

    public static boolean isAvailable() {
        return available && shadowFont != null && MsdfRenderTypes.getMsdfShader() != null;
    }

    public static boolean hasFontFace() {
        return available && shadowFont != null;
    }

    public static Font shadowFont() {
        return shadowFont;
    }

    public static MsdfGlyphProvider provider() {
        return provider;
    }

    public static MsdfAtlas atlas() {
        return atlas;
    }

    public static float cguiBaseHeight() {
        return CGUI_BASE_HEIGHT;
    }

    public static void shutdown() {
        if (provider != null) {
            provider.close();
            provider = null;
        }
        if (atlas != null) {
            atlas.shutdown();
            atlas = null;
        }
        shadowFont = null;
        available = false;
    }

    public static void clientTick() {
        MsdfGlowAnimator.clientTick();
    }
}

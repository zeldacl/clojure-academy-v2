package cn.li.mc1201.client.font.msdf;

import com.mojang.blaze3d.font.GlyphProvider;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.List;

/**
 * Shadow font: isolated FontSet + Font for mod CGUI text (vanilla TrueTypeGlyphProvider / stitch).
 */
public final class MsdfFontManager {

    private static final Logger LOGGER = LogManager.getLogger();

    public static final ResourceLocation SHADOW_FONT_ID = new ResourceLocation("my_mod", "msdf_shadow");

    public static final float DESIGN_PIXEL_HEIGHT = 32.0f;
    public static final float CGUI_BASE_HEIGHT = 32.0f;

    private static Font shadowFont;
    private static FontSet shadowFontSet;
    private static MsdfFontFace face;
    private static volatile boolean bakeProbed;

    private MsdfFontManager() {
    }

    public static boolean init(final Path fontPath) {
        synchronized (MsdfFontManager.class) {
            if (shadowFont != null) {
                return true;
            }
            try {
                final Minecraft mc = Minecraft.getInstance();
                if (mc == null) {
                    LOGGER.debug("MSDF init deferred: Minecraft not ready");
                    return false;
                }

                face = new MsdfFontFace(fontPath, DESIGN_PIXEL_HEIGHT);
                final GlyphProvider glyphProvider = face.glyphProvider();

                final FontSet fontSet = new FontSet(mc.getTextureManager(), SHADOW_FONT_ID);
                fontSet.reload(List.of(glyphProvider));
                shadowFontSet = fontSet;
                shadowFont = new Font(rl -> shadowFontSet, false);

                if (RenderSystem.isOnRenderThread()) {
                    fontSet.getGlyph('A');
                    fontSet.getGlyph(0x4E2D);
                }

                LOGGER.info(
                        "MSDF shadow font loaded from {} (glyph A={}, U+4E2D={}, pipeline=vanilla-truetype)",
                        fontPath,
                        face.hasGlyph('A'),
                        face.hasGlyph(0x4E2D));
                return true;
            } catch (Exception e) {
                LOGGER.error("MSDF font init failed for {}", fontPath, e);
                shutdown();
                return false;
            }
        }
    }

    public static boolean isAvailable() {
        return hasFontFace();
    }

    public static boolean hasFontFace() {
        return shadowFont != null && face != null;
    }

    public static boolean hasGlyph(final int codePoint) {
        return face != null && face.hasGlyph(codePoint);
    }

    public static Font shadowFont() {
        return shadowFont;
    }

    public static float cguiBaseHeight() {
        return CGUI_BASE_HEIGHT;
    }

    public static void shutdown() {
        if (face != null) {
            face.close();
            face = null;
        }
        shadowFont = null;
        shadowFontSet = null;
        bakeProbed = false;
    }

    public static void clientTick() {
        if (!bakeProbed && shadowFont != null) {
            bakeProbed = true;
            try {
                final int wA = shadowFont.width(Component.literal("A"));
                final int wCjk = shadowFont.width(Component.literal("\u4E2D"));
                LOGGER.info("MSDF shadow bake probe: width(A)={}, width(U+4E2D)={}", wA, wCjk);
            } catch (Exception e) {
                LOGGER.warn("MSDF shadow bake probe failed", e);
            }
        }
        MsdfGlowAnimator.clientTick();
    }
}

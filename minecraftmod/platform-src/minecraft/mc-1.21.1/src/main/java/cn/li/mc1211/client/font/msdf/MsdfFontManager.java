package cn.li.mc1211.client.font.msdf;

import cn.li.mcver.ResourceLocations;
import cn.li.mcmod.ModId;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;

/**
 * Shadow font: isolated FontSet + Font for mod CGUI text.
 * Supports monospace/proportional dual mode via {@link MSDFAwareGlyph}.
 *
 * <p>On 1.21.1 the FreeType-backed {@code TrueTypeGlyphProvider} / FontSet.reload
 * API changed; init is currently a no-op until that port lands.</p>
 */
public final class MsdfFontManager {

    private static final Logger LOGGER = LogManager.getLogger();

    public static final ResourceLocation SHADOW_FONT_ID =
            ResourceLocations.of(ModId.ID, "msdf_shadow");

    public static final float DESIGN_PIXEL_HEIGHT = 32.0f;
    public static final float CGUI_BASE_HEIGHT = 32.0f;

    private static Font shadowFont;
    private static FontSet shadowFontSet;
    private static MsdfFontFace face;
    private static volatile boolean bakeProbed;

    private static volatile boolean monospace;
    private static volatile float monospaceAdvance;

    private MsdfFontManager() {}

    public static boolean init(final Path fontPath) {
        synchronized (MsdfFontManager.class) {
            if (shadowFont != null) return true;
            LOGGER.warn("MSDF shadow font disabled on 1.21.1 (FreeType glyph provider pending); path={}", fontPath);
            return false;
        }
    }

    public static void shutdown() {
        if (face != null) { face.close(); face = null; }
        shadowFont = null;
        shadowFontSet = null;
        bakeProbed = false;
    }

    public static void setMonospace(final boolean v) { monospace = v; }
    public static boolean isMonospace() { return monospace; }

    /** Fixed advance for monospace mode, derived from '0' glyph width. */
    public static float monospaceAdvance() { return monospaceAdvance; }

    public static boolean isAvailable() { return hasFontFace(); }
    public static boolean hasFontFace() { return shadowFont != null && face != null; }
    public static boolean hasGlyph(final int codePoint) {
        return face != null && face.hasGlyph(codePoint);
    }
    public static Font shadowFont() { return shadowFont; }
    public static float cguiBaseHeight() { return CGUI_BASE_HEIGHT; }

    public static void clientTick() {
        if (!bakeProbed && shadowFont != null) {
            bakeProbed = true;
            try {
                monospaceAdvance = shadowFont.width(Component.literal("0"));
                final int wi = shadowFont.width(Component.literal("i"));
                final int wW = shadowFont.width(Component.literal("W"));
                final int wCjk = shadowFont.width(Component.literal("中"));
                LOGGER.info("MSDF probe: i={} W={} U+4E2D={} monoAdv={}",
                        wi, wW, wCjk, monospaceAdvance);
            } catch (Exception e) {
                LOGGER.warn("MSDF probe failed", e);
            }
        }
    }
}

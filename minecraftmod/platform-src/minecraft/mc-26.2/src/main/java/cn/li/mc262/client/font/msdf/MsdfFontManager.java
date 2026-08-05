package cn.li.mc262.client.font.msdf;

import java.nio.file.Path;
import net.minecraft.client.gui.Font;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import cn.li.mcver.ResourceLocations;

/** 26.2 stub MSDF font manager. */
@OnlyIn(Dist.CLIENT)
public final class MsdfFontManager {
    private MsdfFontManager() {}

    public static final Identifier SHADOW_FONT_ID = ResourceLocations.of("academy", "msdf_shadow");
    public static final float DESIGN_PIXEL_HEIGHT = 32.0f;
    public static final float CGUI_BASE_HEIGHT = 32.0f;

    public static boolean init(final Path fontPath) { return false; }
    public static void shutdown() {}
    public static void setMonospace(final boolean v) {}
    public static boolean isMonospace() { return false; }
    public static float monospaceAdvance() { return 0f; }
    public static boolean isAvailable() { return false; }
    public static boolean hasFontFace() { return false; }
    public static boolean hasGlyph(final int codePoint) { return false; }
    public static Font shadowFont() { return null; }
    public static float cguiBaseHeight() { return CGUI_BASE_HEIGHT; }
    public static void clientTick() {}
}

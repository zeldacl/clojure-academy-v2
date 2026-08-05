package cn.li.mc262.client;

import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 26.2 stub: GuiGraphics removed/reorganized. Real blit path needs GuiGraphicsExtractor rewrite.
 */
@OnlyIn(Dist.CLIENT)
public final class GuiGraphicsHelper {
    private GuiGraphicsHelper() {}

    public static void blit9(Object graphics, Identifier texture,
                             int x, int y, int w, int h,
                             int u, int v, int regionW, int regionH,
                             int texW, int texH,
                             int borderL, int borderT, int borderR, int borderB) {
        // TODO 26.2
    }

    public static void blitTexturedQuad(Object graphics, Identifier texture,
                                        int x0, int y0, int x1, int y1,
                                        float u0, float v0, float u1, float v1) {
        // TODO 26.2
    }
}

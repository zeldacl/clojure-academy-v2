package cn.li.mc1201.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

public final class GuiGraphicsHelper {
    private GuiGraphicsHelper() {
    }

    /**
     * Wrapper for GuiGraphics.blit() 9-parameter overload.
     * Provides explicit method signature for Clojure interop.
     */
    public static void blit9(
            GuiGraphics graphics,
            ResourceLocation texture,
            int x, int y,
            int u, int v,
            int width, int height,
            int textureWidth, int textureHeight) {
        graphics.blit(texture, x, y, u, v, width, height, textureWidth, textureHeight);
    }
}
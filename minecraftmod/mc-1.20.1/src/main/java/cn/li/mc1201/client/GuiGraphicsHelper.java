package cn.li.mc1201.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

public final class GuiGraphicsHelper {
    private GuiGraphicsHelper() {
    }

    /**
     * Wrapper for GuiGraphics.blit() 9-parameter overload.
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

    /**
     * Wrapper for GuiGraphics.innerBlit() with normalized UV coordinates.
     * Uses Mixin @Invoker to access the package-private method without reflection.
     */
    public static void innerBlit10(
            GuiGraphics graphics,
            ResourceLocation texture,
            int x1, int x2,
            int y1, int y2,
            int z,
            float u0, float u1,
            float v0, float v1) {
        ((cn.li.forge1201.mixin.GuiGraphicsInvoker) graphics).invokeInnerBlit(
                texture, x1, x2, y1, y2, z, u0, u1, v0, v1);
    }
}
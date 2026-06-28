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

    /**
     * Wrapper for GuiGraphics.innerBlit() with normalized UV coordinates.
     * Uses reflection to access the package-private method in GuiGraphics.
     * Provides explicit method signature for Clojure interop (avoids reflection).
     */
    public static void innerBlit10(
            GuiGraphics graphics,
            ResourceLocation texture,
            int x1, int x2,
            int y1, int y2,
            int z,
            float u0, float u1,
            float v0, float v1) {
        try {
            java.lang.reflect.Method method = GuiGraphics.class.getDeclaredMethod(
                "innerBlit",
                ResourceLocation.class,
                int.class, int.class,
                int.class, int.class,
                int.class,
                float.class, float.class,
                float.class, float.class
            );
            method.setAccessible(true);
            method.invoke(graphics, texture, x1, x2, y1, y2, z, u0, u1, v0, v1);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke GuiGraphics.innerBlit", e);
        }
    }
}
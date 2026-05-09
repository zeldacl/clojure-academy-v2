package cn.li.forge1201.client;

public final class GuiGraphicsHelper {
    private GuiGraphicsHelper() {
    }

    public static void blit9(
            net.minecraft.client.gui.GuiGraphics graphics,
            net.minecraft.resources.ResourceLocation texture,
            int x, int y,
            int u, int v,
            int width, int height,
            int textureWidth, int textureHeight) {
        cn.li.mc1201.client.GuiGraphicsHelper.blit9(
                graphics, texture, x, y, u, v, width, height, textureWidth, textureHeight);
    }
}

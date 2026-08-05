package cn.li.mc262.client;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 26.2 client helpers shared by loader adapters.
 * Texture preload uses {@link net.minecraft.client.renderer.texture.TextureManager#getTexture}
 * ({@code bindForSetup} was removed). BER / menu screens register via
 * {@code EntityRenderersEvent} / {@code RegisterMenuScreensEvent} — not here.
 */
@OnlyIn(Dist.CLIENT)
public final class ClientHelper {
    private ClientHelper() {}

    public static void bindTextureForSetup(Identifier texture) {
        if (texture == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getTextureManager() == null) {
            return;
        }
        // Force-load; bindForSetup was removed in the 26.2 texture rewrite.
        minecraft.getTextureManager().getTexture(texture);
    }
}

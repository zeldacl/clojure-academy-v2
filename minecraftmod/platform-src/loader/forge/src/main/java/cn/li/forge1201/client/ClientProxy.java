package cn.li.forge1201.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Client-side proxy for accessing Minecraft client instance.
 * This class is only loaded on the client side, preventing server crashes.
 */
@OnlyIn(Dist.CLIENT)
public class ClientProxy {

    public static Minecraft getMinecraft() {
        return Minecraft.getInstance();
    }

    public static Font getFont() {
        return Minecraft.getInstance().font;
    }
}

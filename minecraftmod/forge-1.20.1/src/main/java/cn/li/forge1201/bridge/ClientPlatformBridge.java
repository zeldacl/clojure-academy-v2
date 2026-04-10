package cn.li.forge1201.bridge;

import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Client-only bridge for accessing client-side MC classes from Clojure AOT code
 * without string-based Class.forName calls.
 *
 * This class is stripped from the server JAR by the @OnlyIn annotation.
 * Always call from Clojure through a client-side check (side/client-side?).
 */
@OnlyIn(Dist.CLIENT)
public final class ClientPlatformBridge {
    private ClientPlatformBridge() {}

    @OnlyIn(Dist.CLIENT)
    public static Class<?> getLocalPlayerClass() {
        return LocalPlayer.class;
    }
}

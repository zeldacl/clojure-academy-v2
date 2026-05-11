package cn.li.fabric1201.bridge;

import net.minecraft.client.player.LocalPlayer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Client-only bridge for accessing client-side MC classes from Clojure AOT code
 * without string-based Class.forName calls.
 *
 * This class is only available in the client environment.
 * Always call from Clojure through a client-side check (side/client-side?).
 */
@Environment(EnvType.CLIENT)
public final class ClientPlatformBridge {
    private ClientPlatformBridge() {}

    @Environment(EnvType.CLIENT)
    public static Class<?> getLocalPlayerClass() {
        return LocalPlayer.class;
    }
}

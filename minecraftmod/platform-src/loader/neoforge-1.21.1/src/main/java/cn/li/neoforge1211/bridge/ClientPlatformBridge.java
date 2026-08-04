package cn.li.neoforge1211.bridge;

import cn.li.mc1211.client.ClientClassAccessor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

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
        return ClientClassAccessor.getLocalPlayerClass();
    }
}

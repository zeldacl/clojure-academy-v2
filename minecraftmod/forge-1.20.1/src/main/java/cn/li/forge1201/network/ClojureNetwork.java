package cn.li.forge1201.network;

import clojure.lang.IFn;
import net.minecraft.server.level.ServerPlayer;

/**
 * Forge 1.20.1 SimpleChannel bridge for Clojure GUI RPC network system.
 */
public class ClojureNetwork {
    private ClojureNetwork() {
    }

    /**
     * Register the SimpleChannel and both packet types.
     * Must be called during mod common setup (before any packet is sent).
     *
     * @param reqHandler  IFn(String msgId, int requestId, byte[] payload, ServerPlayer sender)
     * @param respHandler IFn(int requestId, byte[] response)
     */
    public static void init(IFn reqHandler, IFn respHandler) {
        NetworkHandlerRegistry.install(reqHandler, respHandler);
        ClojureNetworkChannel.initialize();
    }

    public static void sendToServer(String msgId, int requestId, byte[] payload) {
        ClojureNetworkChannel.sendToServer(msgId, requestId, payload);
    }

    public static void sendToClient(ServerPlayer player, int requestId, byte[] response) {
        ClojureNetworkChannel.sendToClient(player, requestId, response);
    }
}

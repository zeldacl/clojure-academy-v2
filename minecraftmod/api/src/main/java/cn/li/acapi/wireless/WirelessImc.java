package cn.li.acapi.wireless;

/**
 * Inter-Mod Communication (IMC) constants for the wireless energy system.
 *
 * <p>External mods that want to hook into the wireless system use these constants
 * to send IMC messages during the {@code InterModEnqueueEvent} phase:
 *
 * <pre>{@code
 * // In your FMLCommonSetup or InterModEnqueueEvent:
 * InterModComms.sendTo(
 *     WirelessImc.MOD_ID,
 *     WirelessImc.REGISTER_NETWORK_HANDLER,
 *     MyNetworkHandler::new   // Supplier<WirelessNetworkHandler>
 * );
 * }</pre>
 *
 * <p>This mod processes these messages during {@code InterModProcessEvent} and
 * invokes registered handlers at the appropriate wireless lifecycle points.
 * A handler that throws an exception is removed silently (logged at DEBUG level).
 *
 * <p><b>Handler contracts:</b> see the nested interfaces below.
 */
public final class WirelessImc {

    /** The mod-id of this mod; use as the target of {@code InterModComms.sendTo}. */
    public static final String MOD_ID = "academycraft";

    /**
     * IMC key: register a {@link NetworkEventHandler}.
     * Payload must be a {@code Supplier<NetworkEventHandler>}.
     */
    public static final String REGISTER_NETWORK_HANDLER = "register_wireless_network_handler";

    /**
     * IMC key: register a {@link NodeEventHandler}.
     * Payload must be a {@code Supplier<NodeEventHandler>}.
     */
    public static final String REGISTER_NODE_HANDLER = "register_wireless_node_handler";

    private WirelessImc() {}

    // -------------------------------------------------------------------------
    // Handler interfaces
    // -------------------------------------------------------------------------

    /**
     * Callback interface for mods that want to observe wireless network lifecycle.
     * Implementations must be safe to call from the server thread.
     */
    @FunctionalInterface
    public interface NetworkEventHandler {
        /**
         * Called when a wireless network event occurs.
         *
         * @param type  one of {@code "created"}, {@code "destroyed"}
         * @param ssid  the network SSID
         * @param matrix the matrix tile interface
         */
        void onNetworkEvent(String type, String ssid, cn.li.acapi.wireless.IWirelessMatrix matrix);
    }

    /**
     * Callback interface for mods that want to observe node connection events.
     * Implementations must be safe to call from the server thread.
     */
    @FunctionalInterface
    public interface NodeEventHandler {
        /**
         * Called when a node connection event occurs.
         *
         * @param type  one of {@code "connected"}, {@code "disconnected"}, {@code "generator_linked"},
         *              {@code "generator_unlinked"}, {@code "receiver_linked"}, {@code "receiver_unlinked"}
         * @param node  the node tile interface
         */
        void onNodeEvent(String type, cn.li.acapi.wireless.IWirelessNode node);
    }
}

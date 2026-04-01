package cn.li.acapi.wireless.event;

import cn.li.acapi.wireless.IWirelessMatrix;
import cn.li.acapi.wireless.IWirelessNode;
import cn.li.acapi.wireless.IWirelessGenerator;
import cn.li.acapi.wireless.IWirelessReceiver;
import net.minecraftforge.eventbus.api.Event;

/**
 * Hierarchy of events fired on the Forge event bus when the wireless energy
 * network state changes.
 *
 * <p>External mods subscribe like this:
 * <pre>{@code
 * @Mod.EventBusSubscriber(modid = "yourmod", bus = Mod.EventBusSubscriber.Bus.FORGE)
 * public class YourWirelessListener {
 *     @SubscribeEvent
 *     public static void onNetworkCreated(WirelessNetworkEvent.NetworkCreated e) {
 *         IWirelessMatrix matrix = e.getMatrix();
 *         // ...
 *     }
 * }
 * }</pre>
 *
 * <p>All subclasses are fired on the <em>server-side Forge event bus</em> (not the mod bus).
 * Client-side projection or GUI updates should be driven by network packets, not these events.
 */
public abstract class WirelessNetworkEvent extends Event {

    private final IWirelessMatrix matrix;

    protected WirelessNetworkEvent(IWirelessMatrix matrix) {
        this.matrix = matrix;
    }

    /**
     * Returns the {@link IWirelessMatrix} that owns the network this event concerns.
     * The matrix is always present when the event fires; it may become absent
     * later if the block is broken.
     */
    public IWirelessMatrix getMatrix() { return matrix; }

    // -------------------------------------------------------------------------
    // Network lifecycle
    // -------------------------------------------------------------------------

    /** Fired after a wireless network is created (matrix initialised with an SSID). */
    public static final class NetworkCreated extends WirelessNetworkEvent {
        private final String ssid;
        public NetworkCreated(IWirelessMatrix matrix, String ssid) {
            super(matrix);
            this.ssid = ssid;
        }
        public String getSsid() { return ssid; }
    }

    /** Fired just before a wireless network is destroyed (matrix broken or manually reset). */
    public static final class NetworkDestroyed extends WirelessNetworkEvent {
        private final String ssid;
        public NetworkDestroyed(IWirelessMatrix matrix, String ssid) {
            super(matrix);
            this.ssid = ssid;
        }
        public String getSsid() { return ssid; }
    }

    // -------------------------------------------------------------------------
    // Node membership
    // -------------------------------------------------------------------------

    /** Fired after a node is connected to a network. */
    public static final class NodeConnected extends WirelessNetworkEvent {
        private final IWirelessNode node;
        public NodeConnected(IWirelessMatrix matrix, IWirelessNode node) {
            super(matrix);
            this.node = node;
        }
        public IWirelessNode getNode() { return node; }
    }

    /** Fired after a node is disconnected from a network. */
    public static final class NodeDisconnected extends WirelessNetworkEvent {
        private final IWirelessNode node;
        public NodeDisconnected(IWirelessMatrix matrix, IWirelessNode node) {
            super(matrix);
            this.node = node;
        }
        public IWirelessNode getNode() { return node; }
    }

    // -------------------------------------------------------------------------
    // Device connections (generator / receiver on a node)
    // -------------------------------------------------------------------------

    /** Fired after a generator is linked to a node. */
    public static final class GeneratorLinked extends Event {
        private final IWirelessNode node;
        private final IWirelessGenerator generator;
        public GeneratorLinked(IWirelessNode node, IWirelessGenerator generator) {
            this.node = node;
            this.generator = generator;
        }
        public IWirelessNode getNode() { return node; }
        public IWirelessGenerator getGenerator() { return generator; }
    }

    /** Fired after a receiver is linked to a node. */
    public static final class ReceiverLinked extends Event {
        private final IWirelessNode node;
        private final IWirelessReceiver receiver;
        public ReceiverLinked(IWirelessNode node, IWirelessReceiver receiver) {
            this.node = node;
            this.receiver = receiver;
        }
        public IWirelessNode getNode() { return node; }
        public IWirelessReceiver getReceiver() { return receiver; }
    }
}

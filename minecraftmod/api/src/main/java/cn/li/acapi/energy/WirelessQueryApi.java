package cn.li.acapi.energy;

import cn.li.acapi.energy.handle.BlockPoint;
import cn.li.acapi.energy.handle.NodeConnectionRef;
import cn.li.acapi.energy.handle.WirelessNetworkRef;
import cn.li.acapi.energy.handle.WorldHandle;
import cn.li.acapi.wireless.IWirelessGenerator;
import cn.li.acapi.wireless.IWirelessMatrix;
import cn.li.acapi.wireless.IWirelessNode;
import cn.li.acapi.wireless.IWirelessReceiver;
import cn.li.acapi.wireless.IWirelessUser;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Typed wireless helper facade.
 */
public final class WirelessQueryApi {

    public interface Bridge {
        WirelessNetworkRef getWirelessNetByMatrix(IWirelessMatrix matrix);

        WirelessNetworkRef getWirelessNetByNode(IWirelessNode node);

        List<WirelessNetworkRef> getNetInRange(WorldHandle world, BlockPoint center, double range, int maxResults);

        NodeConnectionRef getNodeConnByNode(IWirelessNode node);

        NodeConnectionRef getNodeConnByUser(IWirelessUser user);

        List<IWirelessNode> getNodesInRange(WorldHandle world, BlockPoint center);
    }

    private static final Bridge NO_OP = new Bridge() {
        @Override
        public WirelessNetworkRef getWirelessNetByMatrix(IWirelessMatrix matrix) {
            return null;
        }

        @Override
        public WirelessNetworkRef getWirelessNetByNode(IWirelessNode node) {
            return null;
        }

        @Override
        public List<WirelessNetworkRef> getNetInRange(WorldHandle world, BlockPoint center, double range, int maxResults) {
            return Collections.emptyList();
        }

        @Override
        public NodeConnectionRef getNodeConnByNode(IWirelessNode node) {
            return null;
        }

        @Override
        public NodeConnectionRef getNodeConnByUser(IWirelessUser user) {
            return null;
        }

        @Override
        public List<IWirelessNode> getNodesInRange(WorldHandle world, BlockPoint center) {
            return Collections.emptyList();
        }
    };

    private static volatile Bridge bridge = NO_OP;

    private WirelessQueryApi() {}

    public static void installBridge(Bridge runtimeBridge) {
        bridge = Objects.requireNonNull(runtimeBridge, "runtimeBridge");
    }

    public static Optional<WirelessNetworkRef> getWirelessNet(IWirelessMatrix matrix) {
        return Optional.ofNullable(bridge.getWirelessNetByMatrix(matrix));
    }

    public static Optional<WirelessNetworkRef> getWirelessNet(IWirelessNode node) {
        return Optional.ofNullable(bridge.getWirelessNetByNode(node));
    }

    public static boolean isNodeLinked(IWirelessNode node) {
        return getWirelessNet(node).isPresent();
    }

    public static boolean isMatrixActive(IWirelessMatrix matrix) {
        return getWirelessNet(matrix).isPresent();
    }

    public static List<WirelessNetworkRef> getNetInRange(WorldHandle world, BlockPoint center, double range, int maxResults) {
        return bridge.getNetInRange(world, center, range, maxResults);
    }

    public static Optional<NodeConnectionRef> getNodeConn(IWirelessNode node) {
        return Optional.ofNullable(bridge.getNodeConnByNode(node));
    }

    public static Optional<NodeConnectionRef> getNodeConn(IWirelessUser user) {
        return Optional.ofNullable(bridge.getNodeConnByUser(user));
    }

    public static boolean isReceiverLinked(IWirelessReceiver rec) {
        return getNodeConn(rec).isPresent();
    }

    public static boolean isGeneratorLinked(IWirelessGenerator gen) {
        return getNodeConn(gen).isPresent();
    }

    public static List<IWirelessNode> getNodesInRange(WorldHandle world, BlockPoint center) {
        return bridge.getNodesInRange(world, center);
    }
}

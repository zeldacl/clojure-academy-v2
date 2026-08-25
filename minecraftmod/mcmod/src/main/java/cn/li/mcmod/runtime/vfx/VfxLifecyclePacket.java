package cn.li.mcmod.runtime.vfx;
import java.util.UUID;
public record VfxLifecyclePacket(VfxPacketKind kind, long worldEpoch, long instanceId, int assetId, long stateSequence, long eventSequence, UUID owner, long startServerTick, long seed) implements VfxPacket {
    public VfxLifecyclePacket {
        if (kind == null || kind == VfxPacketKind.CATALOG_HELLO || kind == VfxPacketKind.CATALOG_ACK) throw new IllegalArgumentException("lifecycle kind");
        if (owner == null) throw new NullPointerException("owner");
    }
}
package cn.li.mcmod.runtime.vfx;
import java.util.UUID;
public record VfxLifecyclePacket(VfxPacketKind kind, long worldEpoch, long instanceId, int assetId, long stateSequence, long eventSequence, UUID owner, long startServerTick, long seed) implements VfxPacket {
    public VfxLifecyclePacket {
        if (kind == null || kind == VfxPacketKind.CATALOG_HELLO || kind == VfxPacketKind.CATALOG_ACK) throw new IllegalArgumentException("lifecycle kind");
        if (worldEpoch < 0 || instanceId < 0 || assetId < 0 || stateSequence < 0 || eventSequence < 0 || startServerTick < 0) throw new IllegalArgumentException("negative lifecycle field");
        if (owner == null) throw new NullPointerException("owner");
    }
}
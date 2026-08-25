package cn.li.mcmod.runtime.vfx;
import java.util.UUID;
public interface VfxTransport {
    void sendOwner(UUID owner, VfxPacket packet);
    void sendTracking(UUID anchorEntity, VfxPacket packet);
    void sendDimension(String dimensionId, VfxPacket packet);
}
package cn.li.mcmod.runtime.vfx;
public record VfxCatalogAck(int protocolVersion, long catalogHash, boolean accepted, String reason) implements VfxPacket {
    public VfxCatalogAck {
        if (protocolVersion <= 0 || reason == null) throw new IllegalArgumentException("invalid catalog ack");
    }
    @Override public VfxPacketKind kind() { return VfxPacketKind.CATALOG_ACK; }
}
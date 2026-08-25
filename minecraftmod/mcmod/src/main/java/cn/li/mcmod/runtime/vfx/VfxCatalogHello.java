package cn.li.mcmod.runtime.vfx;
public record VfxCatalogHello(int protocolVersion, long catalogHash, int maxPacketBytes, int maxParameters) implements VfxPacket {
    public VfxCatalogHello {
        if (protocolVersion <= 0 || maxPacketBytes <= 0 || maxParameters <= 0) throw new IllegalArgumentException("invalid catalog hello");
    }
    @Override public VfxPacketKind kind() { return VfxPacketKind.CATALOG_HELLO; }
}